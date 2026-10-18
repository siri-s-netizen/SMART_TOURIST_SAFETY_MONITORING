"""
weather_eda.py - real historical weather EDA for the tourist-safety project.

Why this module exists
----------------------
Heat, cold, heavy rain and strong wind are the environmental hazards a tourist
actually meets. ``DATASETS.pdf`` names the **OpenWeatherMap** API, but that
service needs a paid/registered key for history, so this module supports both:

  * if the environment variable ``OPENWEATHER_API_KEY`` is set -> fetch real
    observations from OpenWeatherMap;
  * otherwise -> fall back to the key-free **Open-Meteo** historical archive
    (https://archive-api.open-meteo.com/v1/archive).

The provider that was actually used is stated in ``findings.md`` and stamped on
every figure. Open-Meteo data is NEVER presented as OpenWeatherMap data.

Why these two sites
-------------------
* **site A - "geolife_centroid"**: the robust centroid (component-wise median)
  of the cleaned GeoLife point sample, i.e. the centre of where the tourists in
  this project actually are. Why median and not mean: the mean is dragged to the
  Pacific (lon ~ -179.7) by a handful of long-haul traces, so the median is the
  honest representative point. Both numbers are reported.
* **site B - "huangshan_trek"**: Huangshan (Yellow Mountain) Scenic Area,
  Anhui, a flagship Chinese trekking/tourist area, chosen as a plausible
  second deployment site with very different terrain and climate from Beijing.
  Its coordinates are an analyst-chosen representative point, NOT derived from
  GeoLife, and are labelled as such.

Every number reported comes from the cached JSON response that this script
wrote. If a fetch fails the run writes ``NOT_RUN.md`` and exits non-zero.

Outputs (``ml/outputs/weather/``)
---------------------------------
* ``fig_01_daily_temperature.png``   max / min / range lines
* ``fig_02_monthly_precipitation.png``
* ``fig_03_humidity_wind_distribution.png``
* ``fig_04_harsh_weather_days.png``
* ``table_01..table_04*.csv``
* ``findings.md``

Run:  python ml/weather_eda.py [--refresh] [--start 2011-01-01] [--end 2012-07-27]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import common as C  # noqa: E402

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #

OPENMETEO_ARCHIVE = "https://archive-api.open-meteo.com/v1/archive"
OPENWEATHER_TIMEMACHINE = "https://api.openweathermap.org/data/3.0/onecall/timemachine"
USER_AGENT = "TouristSafetyMonitoring-ml/1.0 (academic EDA)"
HTTP_TIMEOUT_S = 120

#: default span: the calendar years that dominate the GeoLife sample, ending on
#: the dataset's last recorded day (2012-07-27).
DEFAULT_START = "2011-01-01"
DEFAULT_END = "2012-07-27"

#: Huangshan Scenic Area representative point (analyst-chosen, not from GeoLife)
HUANGSHAN_LAT = 30.1340
HUANGSHAN_LON = 118.1610

WEATHER_DATA_DIR = C.DATA_DIR / "weather"
WEATHER_OUT_DIR = C.OUTPUTS_DIR / "weather"

HOURLY_VARS = ["temperature_2m", "relative_humidity_2m", "precipitation", "wind_speed_10m"]

#: explicit, documented "harsh weather day" rule. A day is harsh if ANY holds.
HARSH_THRESHOLDS = {
    "precipitation_sum_mm": 10.0,   # >= 10 mm rain/snow in a day (heavy)
    "temperature_2m_min_c": -5.0,   # <= -5 C minimum (hard freeze, exposure risk)
    "temperature_2m_max_c": 35.0,   # >= 35 C maximum (heat-stress risk)
    "wind_speed_10m_kmh": 30.0,     # >= 30 km/h mean wind (~fresh gale, ~8.3 m/s)
}
HARSH_ATTR = "Open-Meteo historical archive"  # overwritten with the real provider at runtime


class WeatherProviderError(RuntimeError):
    """Raised when the weather provider cannot be reached / queried."""


# --------------------------------------------------------------------------- #
# URL construction (pure)
# --------------------------------------------------------------------------- #

def openmeteo_url(lat: float, lon: float, start: str, end: str) -> str:
    """Exact Open-Meteo archive URL (daily tmin/tmax + four hourly variables)."""
    params = {
        "latitude": f"{lat:.5f}",
        "longitude": f"{lon:.5f}",
        "start_date": start,
        "end_date": end,
        "hourly": ",".join(HOURLY_VARS),
        "daily": "temperature_2m_max,temperature_2m_min",
        "timezone": "UTC",
    }
    return OPENMETEO_ARCHIVE + "?" + urllib.parse.urlencode(params)


def openweather_url(lat: float, lon: float, dt: int, api_key: str, units: str = "metric") -> str:
    """Exact OpenWeatherMap One-Call 3.0 time-machine URL for one UTC instant."""
    params = {
        "lat": f"{lat:.4f}",
        "lon": f"{lon:.4f}",
        "dt": str(int(dt)),
        "units": units,
        "appid": api_key,
    }
    return OPENWEATHER_TIMEMACHINE + "?" + urllib.parse.urlencode(params)


# --------------------------------------------------------------------------- #
# Parsing / aggregation (pure, unit-tested)
# --------------------------------------------------------------------------- #

def hourly_frame(payload: dict) -> pd.DataFrame:
    """Turn an Open-Meteo ``hourly`` block into a tidy, time-indexed frame."""
    hourly = payload.get("hourly") or {}
    if not hourly.get("time"):
        return pd.DataFrame(columns=["time", *HOURLY_VARS])
    frame = pd.DataFrame({"time": pd.to_datetime(hourly["time"])})
    for var in HOURLY_VARS:
        frame[var] = pd.to_numeric(pd.Series(hourly.get(var)), errors="coerce")
    return frame


def daily_frame(payload: dict) -> pd.DataFrame:
    """The Open-Meteo ``daily`` block (tmin / tmax) as a frame keyed by date."""
    daily = payload.get("daily") or {}
    if not daily.get("time"):
        return pd.DataFrame(columns=["date", "temperature_2m_max", "temperature_2m_min"])
    frame = pd.DataFrame({"date": pd.to_datetime(daily["time"])})
    frame["temperature_2m_max"] = pd.to_numeric(pd.Series(daily.get("temperature_2m_max")), errors="coerce")
    frame["temperature_2m_min"] = pd.to_numeric(pd.Series(daily.get("temperature_2m_min")), errors="coerce")
    return frame


def daily_from_hourly(hourly: pd.DataFrame) -> pd.DataFrame:
    """Aggregate hourly observations to daily means/sums (pure)."""
    if hourly.empty:
        return pd.DataFrame(
            columns=["date", "temperature_2m_mean", "precipitation_sum",
                     "relative_humidity_2m_mean", "wind_speed_10m_mean"]
        )
    work = hourly.copy()
    work["date"] = pd.to_datetime(work["time"]).dt.floor("D")
    grouped = work.groupby("date", sort=True)
    out = pd.DataFrame(
        {
            "temperature_2m_mean": grouped["temperature_2m"].mean(),
            "precipitation_sum": grouped["precipitation"].sum(min_count=1),
            "relative_humidity_2m_mean": grouped["relative_humidity_2m"].mean(),
            "wind_speed_10m_mean": grouped["wind_speed_10m"].mean(),
        }
    ).reset_index()
    return out


def merge_daily(daily_api: pd.DataFrame, daily_agg: pd.DataFrame) -> pd.DataFrame:
    """Combine API tmin/tmax with hourly-derived daily aggregates on the date."""
    if daily_api.empty:
        return daily_agg.copy()
    out = daily_api.merge(daily_agg, on="date", how="left")
    out["temperature_2m_mean"] = out["temperature_2m_mean"].fillna(
        (out["temperature_2m_max"] + out["temperature_2m_min"]) / 2.0
    )
    out["temperature_2m_range"] = out["temperature_2m_max"] - out["temperature_2m_min"]
    return out


def monthly_precipitation(daily: pd.DataFrame) -> pd.DataFrame:
    """Total precipitation per calendar month (pure)."""
    if daily.empty:
        return pd.DataFrame(columns=["month", "precipitation_mm"])
    work = daily.copy()
    work["month"] = pd.to_datetime(work["date"]).dt.to_period("M").astype(str)
    grouped = work.groupby("month", sort=True)["precipitation_sum"].sum(min_count=1)
    return grouped.rename("precipitation_mm").reset_index()


def harsh_weather_days(
    daily: pd.DataFrame, thresholds: dict = HARSH_THRESHOLDS
) -> pd.DataFrame:
    """
    Flag "harsh weather days" using explicit, documented thresholds.

    A day is harsh when ANY of these hold:
      * daily precipitation >= ``precipitation_sum_mm`` mm,
      * daily minimum temperature <= ``temperature_2m_min_c`` C,
      * daily maximum temperature >= ``temperature_2m_max_c`` C,
      * daily mean wind speed >= ``wind_speed_10m_kmh`` km/h.

    Returns the input rows (one per day) plus four boolean flags, an ``is_harsh``
    flag and a human-readable ``reason`` string. Pure; no I/O.
    """
    if daily.empty:
        return pd.DataFrame(columns=["date", "is_harsh", "reason"])
    out = daily.copy()
    out["harsh_rain"] = out["precipitation_sum"] >= thresholds["precipitation_sum_mm"]
    out["harsh_cold"] = out["temperature_2m_min"] <= thresholds["temperature_2m_min_c"]
    out["harsh_heat"] = out["temperature_2m_max"] >= thresholds["temperature_2m_max_c"]
    out["harsh_wind"] = out["wind_speed_10m_mean"] >= thresholds["wind_speed_10m_kmh"]
    flags = ["harsh_rain", "harsh_cold", "harsh_heat", "harsh_wind"]
    out["is_harsh"] = out[flags].any(axis=1)
    labels = {
        "harsh_rain": f"rain>={thresholds['precipitation_sum_mm']:.0f}mm",
        "harsh_cold": f"min<={thresholds['temperature_2m_min_c']:.0f}C",
        "harsh_heat": f"max>={thresholds['temperature_2m_max_c']:.0f}C",
        "harsh_wind": f"wind>={thresholds['wind_speed_10m_kmh']:.0f}km/h",
    }

    def reason(row) -> str:
        return "; ".join(labels[f] for f in flags if bool(row[f]))

    out["reason"] = out.apply(reason, axis=1)
    return out


# --------------------------------------------------------------------------- #
# Network
# --------------------------------------------------------------------------- #

def _http_get_json(url: str, timeout_s: int = HTTP_TIMEOUT_S) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout_s) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_openmeteo(lat: float, lon: float, start: str, end: str,
                    timeout_s: int = HTTP_TIMEOUT_S) -> tuple[dict, str]:
    url = openmeteo_url(lat, lon, start, end)
    try:
        return _http_get_json(url, timeout_s), url
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError,
            json.JSONDecodeError, ValueError) as exc:
        raise WeatherProviderError(f"Open-Meteo fetch failed: {type(exc).__name__}: {exc}") from exc


def fetch_openweather(lat: float, lon: float, start: str, end: str,
                      api_key: str, *, max_calls: int = 60,
                      timeout_s: int = HTTP_TIMEOUT_S) -> tuple[pd.DataFrame, list[str]]:
    """
    Fetch real OpenWeatherMap observations at 12:00 UTC daily anchors.

    The free/registered *historical* endpoints are paid, and the time-machine
    endpoint is per-instant, so this samples at most ``max_calls`` evenly spaced
    days across the range and returns those real observations as a daily frame.
    Raises :class:`WeatherProviderError` on the first failed call - a partial
    series is never silently completed.
    """
    start_dt = datetime.strptime(start, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    end_dt = datetime.strptime(end, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    all_days = pd.date_range(start_dt, end_dt, freq="D", tz="UTC")
    if len(all_days) > max_calls:
        picks = np.linspace(0, len(all_days) - 1, max_calls).round().astype(int)
        days = all_days[picks]
    else:
        days = all_days

    rows, urls = [], []
    for day in days:
        noon = day.replace(hour=12, minute=0, second=0, microsecond=0)
        url = openweather_url(lat, lon, int(noon.timestamp()), api_key)
        try:
            payload = _http_get_json(url, timeout_s)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError,
                json.JSONDecodeError, ValueError) as exc:
            raise WeatherProviderError(
                f"OpenWeatherMap fetch failed at {noon.isoformat()}: "
                f"{type(exc).__name__}: {exc}"
            ) from exc
        urls.append(url.split("appid=")[0] + "appid=<redacted>")
        obs = (payload.get("data") or [{}])[0]
        rain = float((obs.get("rain") or {}).get("1h", 0.0))
        rows.append(
            {
                "date": pd.Timestamp(day),
                "temperature_2m_max": obs.get("temp"),
                "temperature_2m_min": obs.get("temp"),
                "temperature_2m_mean": obs.get("temp"),
                "precipitation_sum": rain,
                "relative_humidity_2m_mean": obs.get("humidity"),
                "wind_speed_10m_mean": (float(obs.get("wind_speed", 0.0)) * 3.6),
            }
        )
    frame = pd.DataFrame(rows)
    return frame, urls


# --------------------------------------------------------------------------- #
# Figures
# --------------------------------------------------------------------------- #

def _savefig(fig, path: Path, provider: str, sites_text: str) -> None:
    fig.tight_layout(rect=(0, 0.03, 1, 1))
    fig.text(0.5, 0.005, f"provider: {provider} | {sites_text}",
             ha="center", fontsize=6.5, color="dimgray")
    fig.savefig(path, dpi=120)
    plt.close(fig)
    print(f"  wrote {path.name}")


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    WEATHER_DATA_DIR.mkdir(parents=True, exist_ok=True)
    WEATHER_OUT_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    api_key = os.environ.get("OPENWEATHER_API_KEY")
    provider = "OpenWeatherMap" if api_key else "Open-Meteo"
    print(f"[weather] provider: {provider}")

    # site A coordinates: robust centroid of the cleaned GeoLife sample
    pts = C.load_clean_points()
    centroid = {"lat": float(pts["lat"].median()), "lon": float(pts["lon"].median())}
    mean_centroid = {"lat": float(pts["lat"].mean()), "lon": float(pts["lon"].mean())}
    sites = [
        {
            "id": "geolife_centroid",
            "label": "Robust centroid of the cleaned GeoLife sample (Beijing)",
            "lat": centroid["lat"],
            "lon": centroid["lon"],
            "name": "geolife_centroid",
        },
        {
            "id": "huangshan_trek",
            "label": "Huangshan (Yellow Mountain) Scenic Area, Anhui (trekking/tourist site)",
            "lat": HUANGSHAN_LAT,
            "lon": HUANGSHAN_LON,
            "name": "huangshan_trek",
        },
    ]

    site_frames: dict[str, pd.DataFrame] = {}
    site_meta: dict[str, dict] = {}
    for site in sites:
        cache = WEATHER_DATA_DIR / f"{provider.lower()}_{site['id']}_{args.start}_{args.end}.json"
        hourly = pd.DataFrame()
        urls: list[str] = []
        try:
            if provider == "Open-Meteo":
                if cache.is_file() and not args.refresh:
                    payload = C.read_json(cache)
                    url = openmeteo_url(site["lat"], site["lon"], args.start, args.end)
                    urls = [url]
                    print(f"[weather] {site['id']}: using cache {cache.name}")
                else:
                    payload, url = fetch_openmeteo(site["lat"], site["lon"], args.start, args.end)
                    payload["_meta"] = {"provider": provider, "url": url,
                                        "fetch_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
                    C.write_json(cache, payload)
                    urls = [url]
                    print(f"[weather] {site['id']}: fetched Open-Meteo ({cache.name})")
                hourly = hourly_frame(payload)
                daily = merge_daily(daily_frame(payload), daily_from_hourly(hourly))
            else:
                daily, urls = fetch_openweather(site["lat"], site["lon"], args.start, args.end, api_key)
                C.write_json(cache, {"_meta": {"provider": provider, "urls": urls},
                                     "daily": daily.astype(object).to_dict("records")})
                print(f"[weather] {site['id']}: fetched OpenWeatherMap anchors ({len(daily)} days)")
        except WeatherProviderError as exc:
            (WEATHER_OUT_DIR / "NOT_RUN.md").write_text(
                "# Weather EDA - NOT RUN\n\n"
                f"Provider **{provider}** could not be queried.\n\n```\n{exc}\n```\n\n"
                "No figures or tables were produced. No substitute or synthetic weather "
                "data was generated.\n",
                encoding="utf-8",
            )
            print(f"[weather] NOT RUN: {exc}", file=sys.stderr)
            return 3

        daily["date"] = pd.to_datetime(daily["date"])
        site_frames[site["id"]] = daily
        site_meta[site["id"]] = {
            "label": site["label"], "lat": site["lat"], "lon": site["lon"], "urls": urls,
            "n_days": int(len(daily)), "hourly_rows": int(len(hourly)),
        }
        daily.to_csv(WEATHER_OUT_DIR / f"table_01_daily_{site['id']}.csv", index=False)
        monthly = monthly_precipitation(daily)
        monthly.to_csv(WEATHER_OUT_DIR / f"table_02_monthly_{site['id']}.csv", index=False)
        harsh = harsh_weather_days(daily)
        harsh.to_csv(WEATHER_OUT_DIR / f"table_03_harsh_days_{site['id']}.csv", index=False)
        site_meta[site["id"]]["n_harsh"] = int(harsh["is_harsh"].sum())
        site_meta[site["id"]]["monthly"] = monthly
        site_meta[site["id"]]["harsh"] = harsh
        site_meta[site["id"]]["hourly"] = hourly

    # ---- figures ---------------------------------------------------------- #
    fig, axes = plt.subplots(2, 1, figsize=(11, 8), sharex=True)
    for ax, site in zip(axes, sites):
        daily = site_frames[site["id"]].sort_values("date")
        ax.plot(daily["date"], daily["temperature_2m_max"], color="#c0392b", lw=0.7, label="daily max")
        ax.plot(daily["date"], daily["temperature_2m_min"], color="#1a73e8", lw=0.7, label="daily min")
        ax.fill_between(daily["date"], daily["temperature_2m_min"], daily["temperature_2m_max"],
                        color="orange", alpha=0.15, label="daily range")
        ax.set_title(f"{site['id']} ({site['lat']:.4f}, {site['lon']:.4f})")
        ax.set_ylabel("temperature (C)")
        ax.legend(loc="upper left", fontsize=8)
        ax.grid(alpha=0.3)
    axes[-1].set_xlabel("date")
    fig.suptitle("Daily temperature range, max and min")
    _savefig(fig, WEATHER_OUT_DIR / "fig_01_daily_temperature.png",
             provider, f"sites: {', '.join(s['id'] for s in sites)}")

    fig, ax = plt.subplots(figsize=(11, 4.5))
    width = 0.4
    for offset, site in zip((-width / 2, width / 2), sites):
        monthly = site_meta[site["id"]]["monthly"]
        x = np.arange(len(monthly))
        ax.bar(x + offset, monthly["precipitation_mm"], width, label=site["id"])
    monthly0 = site_meta[sites[0]["id"]]["monthly"]
    ax.set_xticks(np.arange(len(monthly0)))
    ax.set_xticklabels(monthly0["month"], rotation=60, ha="right", fontsize=7)
    ax.set_ylabel("precipitation (mm)")
    ax.set_title("Monthly precipitation totals")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3, axis="y")
    _savefig(fig, WEATHER_OUT_DIR / "fig_02_monthly_precipitation.png",
             provider, f"sites: {', '.join(s['id'] for s in sites)}")

    fig, axes = plt.subplots(1, 2, figsize=(11, 4))
    for panel_ax, column, label, title in (
        (axes[0], "relative_humidity_2m", "relative humidity (%)", "Humidity distribution"),
        (axes[1], "wind_speed_10m", "wind speed (km/h)", "Wind-speed distribution"),
    ):
        for site in sites:
            h = site_meta[site["id"]]["hourly"]
            if h.empty:
                values = site_frames[site["id"]][column + "_mean"].dropna()
            else:
                values = h[column].dropna()
            panel_ax.hist(values, bins=30, alpha=0.5, label=site["id"])
        panel_ax.set_xlabel(label)
        panel_ax.set_ylabel("count")
        panel_ax.set_title(title)
        panel_ax.legend(fontsize=8)
    _savefig(fig, WEATHER_OUT_DIR / "fig_03_humidity_wind_distribution.png",
             provider, f"sites: {', '.join(s['id'] for s in sites)}")

    fig, ax = plt.subplots(figsize=(11, 4.5))
    for offset, site in zip((-width / 2, width / 2), sites):
        harsh = site_meta[site["id"]]["harsh"].copy()
        harsh["month"] = pd.to_datetime(harsh["date"]).dt.to_period("M").astype(str)
        per_month = harsh.groupby("month", sort=True)["is_harsh"].sum()
        x = np.arange(len(per_month))
        ax.bar(x + offset, per_month.values, width, label=site["id"])
    ax.set_xticks(x)
    ax.set_xticklabels(list(per_month.index), rotation=60, ha="right", fontsize=7)
    ax.set_ylabel("harsh days")
    ax.set_title("Harsh-weather days per month (documented thresholds)")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3, axis="y")
    _savefig(fig, WEATHER_OUT_DIR / "fig_04_harsh_weather_days.png",
             provider, f"sites: {', '.join(s['id'] for s in sites)}")

    # ---- summary table ---------------------------------------------------- #
    summary_rows = []
    for site in sites:
        daily = site_frames[site["id"]]
        summary_rows.append(
            {
                "site": site["id"],
                "lat": round(site["lat"], 4),
                "lon": round(site["lon"], 4),
                "n_days": len(daily),
                "temp_mean_c": round(float(daily["temperature_2m_mean"].mean()), 2),
                "temp_min_c": round(float(daily["temperature_2m_min"].min()), 2),
                "temp_max_c": round(float(daily["temperature_2m_max"].max()), 2),
                "precip_total_mm": round(float(daily["precipitation_sum"].sum()), 1),
                "humidity_mean_pct": round(float(daily["relative_humidity_2m_mean"].mean()), 1),
                "wind_mean_kmh": round(float(daily["wind_speed_10m_mean"].mean()), 2),
                "n_harsh_days": site_meta[site["id"]]["n_harsh"],
                "pct_harsh_days": round(100.0 * site_meta[site["id"]]["n_harsh"] / len(daily), 2),
            }
        )
    summary = pd.DataFrame(summary_rows)
    summary.to_csv(WEATHER_OUT_DIR / "table_04_summary.csv", index=False)

    # ---- findings --------------------------------------------------------- #
    f: list[str] = []
    f.append("# Weather EDA findings\n")
    f.append(
        f"- **Provider actually used:** **{provider}**"
        + (" (OpenWeatherMap API, key present in `OPENWEATHER_API_KEY`)"
           if provider == "OpenWeatherMap"
           else " (key-free Open-Meteo historical archive; NOT OpenWeatherMap)")
        + "\n"
    )
    f.append(f"- **Date range:** {args.start} .. {args.end} (UTC)\n")
    f.append(
        f"- **Site A `geolife_centroid`:** {centroid['lat']:.5f} N, {centroid['lon']:.5f} E - "
        f"component-wise median of the {len(pts):,}-point cleaned GeoLife sample. "
        f"(The arithmetic mean is {mean_centroid['lat']:.5f} N, {mean_centroid['lon']:.5f} E; "
        "the median is used because the mean is dragged west by a few long-haul traces.)\n"
    )
    f.append(
        f"- **Site B `huangshan_trek`:** {HUANGSHAN_LAT:.5f} N, {HUANGSHAN_LON:.5f} E - "
        "Huangshan (Yellow Mountain) Scenic Area, Anhui, an analyst-chosen trekking/tourist "
        "deployment point (NOT derived from GeoLife).\n"
    )
    f.append("\n## Exact request URLs\n")
    for site in sites:
        urls = site_meta[site["id"]]["urls"]
        for url in urls[:3]:
            f.append(f"- `{site['id']}`: {url}")
        if len(urls) > 3:
            f.append(f"- `{site['id']}`: ... {len(urls)} OpenWeatherMap calls total (12:00 UTC anchors)")
    f.append("")
    f.append("## Summary numbers (from the cached responses)\n")
    f.append(
        "| site | lat | lon | days | mean T (C) | min T (C) | max T (C) | precip (mm) | RH (%) | wind (km/h) | harsh days | % |\n"
        "|---|---|---|---|---|---|---|---|---|---|---|---|\n"
        + "\n".join(
            f"| {r.site} | {r.lat} | {r.lon} | {r.n_days} | {r.temp_mean_c} | {r.temp_min_c} | "
            f"{r.temp_max_c} | {r.precip_total_mm} | {r.humidity_mean_pct} | {r.wind_mean_kmh} | "
            f"{r.n_harsh_days} | {r.pct_harsh_days} |"
            for r in summary.itertuples()
        )
        + "\n"
    )
    f.append("## Harsh-weather rule (explicit)\n")
    f.append(
        "A day is **harsh** when ANY of: precipitation >= "
        f"{HARSH_THRESHOLDS['precipitation_sum_mm']:.0f} mm; minimum temperature <= "
        f"{HARSH_THRESHOLDS['temperature_2m_min_c']:.0f} C; maximum temperature >= "
        f"{HARSH_THRESHOLDS['temperature_2m_max_c']:.0f} C; mean wind >= "
        f"{HARSH_THRESHOLDS['wind_speed_10m_kmh']:.0f} km/h.\n"
    )
    for site in sites:
        harsh = site_meta[site["id"]]["harsh"]
        counts = {name: int(harsh[name].sum()) for name in
                  ("harsh_rain", "harsh_cold", "harsh_heat", "harsh_wind")}
        f.append(
            f"- `{site['id']}`: **{int(harsh['is_harsh'].sum())}** harsh days of "
            f"{len(harsh)} - rain {counts['harsh_rain']}, cold {counts['harsh_cold']}, "
            f"heat {counts['harsh_heat']}, wind {counts['harsh_wind']}. "
            f"Table `table_03_harsh_days_{site['id']}.csv`.\n"
        )
    f.append("## Reproducibility\n")
    f.append(
        f"- provider selection: `OPENWEATHER_API_KEY` {'set' if api_key else 'not set'}.\n"
        f"- figures/tables produced by `ml/weather_eda.py`; wall time {time.time() - t0:.1f}s.\n"
        f"- caches under `ml/data/weather/`; re-run with `python ml/weather_eda.py` "
        f"(cache) or `--refresh` (re-fetch).\n"
    )
    (WEATHER_OUT_DIR / "findings.md").write_text("\n".join(f), encoding="utf-8")
    print(f"[weather] wrote {WEATHER_OUT_DIR / 'findings.md'}")
    print(f"[weather]           {summary[['site', 'temp_mean_c', 'precip_total_mm', 'n_harsh_days']].to_string(index=False)}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Real historical weather EDA (Open-Meteo; OpenWeatherMap if keyed).")
    p.add_argument("--refresh", action="store_true", help="ignore the cache and re-fetch")
    p.add_argument("--start", default=DEFAULT_START)
    p.add_argument("--end", default=DEFAULT_END)
    return p


def main(argv: "list[str] | None" = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
