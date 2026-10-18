"""
test_weather_eda.py - pytest suite for the pure weather aggregation helpers.

No network: the tests run on a small hand-written Open-Meteo-shaped payload and
exercise the daily aggregation, the monthly totals and the documented
"harsh weather day" threshold rule.
"""

from __future__ import annotations

import pandas as pd
import pytest

import weather_eda as W

# --------------------------------------------------------------------------- #
# fixture: two days of hourly data with obviously-correct aggregates
# --------------------------------------------------------------------------- #

FIXTURE = {
    "latitude": 39.9,
    "longitude": 116.4,
    "hourly_units": {"temperature_2m": "C"},
    "hourly": {
        "time": [
            "2011-01-01T00:00", "2011-01-01T01:00", "2011-01-01T02:00",
            "2011-01-02T00:00", "2011-01-02T01:00",
        ],
        "temperature_2m": [0.0, 2.0, 4.0, 10.0, 20.0],
        "relative_humidity_2m": [40.0, 50.0, 60.0, 80.0, 100.0],
        "precipitation": [0.0, 1.0, 2.0, 5.0, 6.0],
        "wind_speed_10m": [5.0, 5.0, 5.0, 40.0, 40.0],
    },
    "daily": {
        "time": ["2011-01-01", "2011-01-02"],
        "temperature_2m_max": [4.0, 20.0],
        "temperature_2m_min": [-2.0, 8.0],
    },
}


@pytest.fixture()
def daily():
    hourly = W.hourly_frame(FIXTURE)
    return W.merge_daily(W.daily_frame(FIXTURE), W.daily_from_hourly(hourly))


# --------------------------------------------------------------------------- #
# parsing
# --------------------------------------------------------------------------- #

def test_hourly_frame_parses_and_types():
    hourly = W.hourly_frame(FIXTURE)
    assert len(hourly) == 5
    assert pd.api.types.is_datetime64_any_dtype(hourly["time"])
    assert hourly["temperature_2m"].tolist() == [0.0, 2.0, 4.0, 10.0, 20.0]


def test_daily_frame_parses():
    d = W.daily_frame(FIXTURE)
    assert len(d) == 2
    assert d["temperature_2m_min"].min() == -2.0


def test_empty_payload_is_handled():
    assert W.hourly_frame({}).empty
    assert W.daily_frame({}).empty
    assert W.daily_from_hourly(W.hourly_frame({})).empty


# --------------------------------------------------------------------------- #
# aggregation
# --------------------------------------------------------------------------- #

def test_daily_from_hourly_aggregates_correctly():
    agg = W.daily_from_hourly(W.hourly_frame(FIXTURE))
    day1 = agg[agg["date"] == pd.Timestamp("2011-01-01")].iloc[0]
    assert day1["temperature_2m_mean"] == pytest.approx(2.0)
    assert day1["precipitation_sum"] == pytest.approx(3.0)
    assert day1["relative_humidity_2m_mean"] == pytest.approx(50.0)
    assert day1["wind_speed_10m_mean"] == pytest.approx(5.0)


def test_merge_daily_keeps_api_extremes_and_computes_range(daily):
    day1 = daily[daily["date"] == pd.Timestamp("2011-01-01")].iloc[0]
    assert day1["temperature_2m_max"] == 4.0
    assert day1["temperature_2m_min"] == -2.0
    assert day1["temperature_2m_range"] == pytest.approx(6.0)


def test_monthly_precipitation_sums_per_month(daily):
    monthly = W.monthly_precipitation(daily)
    assert len(monthly) == 1  # both days are January 2011
    assert monthly.iloc[0]["precipitation_mm"] == pytest.approx(14.0)


# --------------------------------------------------------------------------- #
# harsh-weather rule
# --------------------------------------------------------------------------- #

def test_harsh_weather_days_flags_expected_rows(daily):
    harsh = W.harsh_weather_days(daily)
    # day 1: rain 3 mm (<10), min -2 (>-5), max 4, wind 5 -> NOT harsh
    day1 = harsh[harsh["date"] == pd.Timestamp("2011-01-01")].iloc[0]
    assert not day1["is_harsh"]
    assert day1["reason"] == ""
    # day 2: rain 11 mm (>=10) AND wind 40 km/h (>=30) AND max 20 -> harsh, two reasons
    day2 = harsh[harsh["date"] == pd.Timestamp("2011-01-02")].iloc[0]
    assert day2["is_harsh"]
    assert "rain>=10mm" in day2["reason"]
    assert "wind>=30km/h" in day2["reason"]


def test_harsh_weather_threshold_boundaries(daily):
    custom = dict(W.HARSH_THRESHOLDS)
    custom["precipitation_sum_mm"] = 100.0
    custom["temperature_2m_min_c"] = -100.0
    custom["temperature_2m_max_c"] = 100.0
    custom["wind_speed_10m_kmh"] = 100.0
    harsh = W.harsh_weather_days(daily, custom)
    assert not harsh["is_harsh"].any()


def test_harsh_weather_empty_input():
    assert W.harsh_weather_days(pd.DataFrame()).empty


# --------------------------------------------------------------------------- #
# url construction
# --------------------------------------------------------------------------- #

def test_openmeteo_url_contains_everything_requested():
    url = W.openmeteo_url(39.97, 116.35, "2011-01-01", "2012-07-27")
    assert url.startswith(W.OPENMETEO_ARCHIVE)
    for var in W.HOURLY_VARS:
        assert var in url
    assert "daily=temperature_2m_max" in url
    assert "timezone=UTC" in url


def test_openweather_url_redacts_nothing_but_carries_the_key_parameter():
    url = W.openweather_url(30.13, 118.16, 1300000000, "SECRET")
    assert url.startswith(W.OPENWEATHER_TIMEMACHINE)
    assert "appid=SECRET" in url
