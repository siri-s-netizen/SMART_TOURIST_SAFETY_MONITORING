# Dataset and EDA — `ml/` analysis module

This document describes the **data-analysis / unsupervised anomaly-detection module**
that lives in `ml/` (a *supplement* to — never a replacement for — the app's
deterministic geo-fencing in `app/`; the module does not touch `app/`).

Everything below was **measured** by running the scripts in `ml/` against the real
Microsoft GeoLife dataset on this machine and reading the produced CSV/JSON/PNG
artifacts (see `ml/outputs/`). No number here is estimated, rounded up from a
guess, or fabricated. Where something cannot be computed it is marked **N/A**.

---

## 1. Dataset: Microsoft Research GeoLife GPS Trajectories 1.3

| Field | Value |
| --- | --- |
| Name | Microsoft Research **GeoLife GPS Trajectories 1.3** |
| Authors | Yu Zheng, Xing Xie, Wei-Ying Ma et al. (Microsoft Research Asia) |
| Download page | https://www.microsoft.com/en-us/download/details.aspx?id=52367 |
| Direct URL used | `https://download.microsoft.com/download/f/4/8/f4894aa5-fdbc-481e-9285-d5f8c4c4f039/Geolife%20Trajectories%201.3.zip` |
| Archive size (verified) | **313,164,406 bytes** (298.7 MB) |
| Obtained in this environment | **YES** — downloaded and unzipped successfully |

### Licence / terms
The archive contains **no `LICENSE`/`COPYING`/`README` file** and the bundled
`User Guide-1.3.pdf` states no explicit open-source licence. The only usage
requirement stated is a **citation request** for the three GeoLife papers (WWW
2009; UbiComp 2008; IEEE DEB 2010). The module therefore treats the data as
**research-use only** and does not redistribute the raw archive. Full details,
exact download/unzip commands and blockers are in `ml/data/README.md`.

### Measured scale (verified)

| Quantity | Value |
| --- | --- |
| User directories | **182** |
| `.plt` trajectory files | **18,670** |
| `labels.txt` (transportation-mode) files | **69** |
| Data lines (points), whole archive | **24,876,978** |
| `User Guide` claim (for comparison) | 17,621 trajectories |

The guide claims 17,621 trajectories while the archive physically contains
18,670 `.plt` files; **both numbers are reported** and the discrepancy is not
hidden.

---

## 2. Verified `.plt` schema (measured, not assumed)

A `.plt` file is ASCII with **6 header lines** and (by default) CRLF endings:

```
Geolife trajectory
WGS 84
Altitude is in Feet
Reserved 3
0,2,255,My Track,0,0,2,8421376
0
```

Each data line then has **7 comma-separated tokens** (the documentation says "6
columns" because the date itself embeds a comma):

```
latitude,longitude,0,altitude,days_since_1899-12-30,YYYY-MM-DD,HH:MM:SS
```

Real example (user `000`):

```
39.984702,116.318417,0,492,39744.1201851852,2008-10-23,02:53:04
```

Verification on the real archive:

* 60 randomly sampled files → **all** had exactly 6 header lines and **0**
  malformed data lines (85,307 data lines checked).
* Full scan of all 18,670 files: **24,876,978 rows parsed, 0 malformed, 0 header
  anomalies**. The two independent time encodings agree to within
  **4.79 × 10⁻⁶ s** (Excel epoch `1899-12-30` + `days` vs. the `YYYY-MM-DD HH:MM:SS`
  string).
* `altitude` is declared "in Feet" but is **unreliable** (many zeros / large
  values); the pipeline **flags** it but never relies on it.

### Critical data quirk
`trajectory_id` (the `.plt` file stem) **collides across users**: 847 ids are
shared by more than one user. Every grouping operation therefore uses the
composite key

```
traj_uid = user_id | trajectory_id
```

Using the bare file name as a group key would silently merge different people's
trajectories (a leakage bug). This is enforced in `ml/common.py::add_traj_uid`.

---

## 3. Preprocessing / cleaning

Implemented in `ml/preprocess.py` (bulk parse + bulk clean, batched to fit
7.9 GB RAM). Cleaning steps (from `common.clean_points`):

1. drop rows with unparsable timestamps;
2. drop out-of-range coordinates and null-island points;
3. drop exact duplicate `(trajectory, timestamp)` rows (keep first);
4. sort chronologically within each trajectory (`traj_uid`);
5. split trajectories into segments at gaps > **120 s**;
6. compute `dt_s / step_m / speed_mps / bearing_deg` per point;
7. flag GPS jumps (`speed > 60 m/s`) and noisy altitude — flagged, not dropped.

### Removal audit (real, whole summary scope = 24,876,978 rows)

| Removed | Count |
| --- | --- |
| Duplicate `(trajectory, timestamp)` rows | **698,900** |
| Invalid-coordinate rows (incl. null-island) | **1** |
| Unparsable-timestamp rows | **0** |
| Malformed rows dropped at parse | **0** |

Outputs (`ml/outputs/clean/`): `trajectory_summary.csv` (**18,670 rows**),
`points_clean_sample.csv` (**1,429,740 points** from 1,200 fixed-seed
trajectories), `preprocess_meta.json`, `sample_manifest.json`. Full run wall
time: **539.2 s**.

---

## 4. Exploratory data analysis (12 required items)

Figures: `ml/outputs/eda/fig_01..fig_12*.png`. Tables:
`ml/outputs/eda/table_01..table_10*.csv`. All numbers are real GeoLife values;
the point-level analyses use the fixed-seed 1,200-trajectory sample
(1,429,740 points), the length/duration analyses use all 18,670 trajectories.

**1. Shape / columns / dtypes.** Cleaned point sample = **1,429,740 rows × 15
columns** (`table_01_schema.csv`); trajectory summary = **18,670 rows × 22
columns**.

**2. Missing values & duplicates.** Across 24,876,978 raw rows: **698,900**
duplicate-timestamp rows, **1** invalid coordinate, **0** unparsable timestamps,
**0** malformed rows. In the cleaned sample, NaN occurs only in the derived
`dt_s/step_m/speed_mps/bearing_deg` columns at segment starts (expected):
`dt_s` missing = **1,200** rows (= one per trajectory).

**3. Spatial extent.** Latitude **1.27681 .. 51.10710**; longitude
**−179.69134 .. 179.99330** (`fig_01_latlon_scatter.png`,
`fig_02_latlon_density.png`). Density is overwhelmingly Beijing.

**4. Trajectory length & duration (all 18,670).** Points/trajectory: median
**504**, mean **1295.0**, max **92,645**. Duration: median **2528 s**, mean
**9675 s**, max **348,431 s** (`fig_03`, `fig_04`). Summed trajectory distance
**1,021,454,302 m ≈ 1,021,454 km**.

**5. Sampling interval.** median `dt` = **2.00 s**, mean **4.74 s**, p95
**5.00 s**, max **3595 s**; **96.3 %** of intervals fall in the dense 1–5 s band
(`fig_05`).

**6. Speed (GPS jumps handled).** **4,525** of 1,423,352 per-step speeds exceed
the 60 m/s jump threshold and are **excluded** from the statistics. Speed excl.
jumps: mean **6.55 m/s**, median **3.23 m/s**, p95 **20.72 m/s**, max
**59.99 m/s** (`fig_06`).

**7. Dwell time / stops.** **557** stops detected (speed < 0.5 m/s sustained
≥ 120 s). Stop duration: median **174 s**, mean **360 s**, max **9945 s**
(`fig_07`).

**8. Time-of-day patterns.** Busiest hour **09:00** with **108,135** fixes;
quietest **21:00** with **11,095** (`fig_08`). Timestamps are local clock time;
**no timezone is stored** in GeoLife.

**9. Spatial trajectories plot.** 60 sample trajectories drawn
(`fig_09_spatial_trajectories.png`).

**10. Outlier analysis.** IQR-flag outliers per engineered feature (top 5):
`alt_change` **679,868 (47.55 %)**, `straightness_w5` **212,483 (14.86 %)**,
`accel_mps2` **192,362 (13.45 %)**, `turn_deg` **175,317 (12.26 %)**,
`altitude` **160,312 (11.21 %)** (`table_08_outlier_summary.csv`,
`fig_10_feature_boxplots.png`).

**11. Correlation heatmap.** Strongest feature correlation:
`speed_mps ~ speed_mean_w5` with **r = 0.979** (expected — they measure the same
motion at different windows) `table_09_correlation.csv`,
`fig_11_correlation_heatmap.png`.

**12. Class balance / labels.** GeoLife `labels.txt` exists for **69 users**
with **14,718** labelled segments, spanning transportation modes:
`walk` 6,460 · `bus` 2,853 · `bike` 2,089 · `taxi` 1,179 · `car` 993 ·
`subway` 813 · `train` 299 · `airplane` 17 · `boat` 7 · `run` 6 ·
`motorcycle` 2 (`table_10_transport_labels.csv`,
`fig_12_transport_mode_balance.png`).

> **There are NO danger / anomaly labels in GeoLife.** The only labels are
> transportation modes, which do not mark anomalies. Class balance for a
> danger/anomaly detection task is therefore **N/A**. This is why the model is
> **unsupervised** and its output is a *movement anomaly score*, never a
> "danger probability".

---

## 5. Reproducibility

* Fixed seed: **42** everywhere.
* Thresholds: jump speed **60 m/s**, segment gap **120 s**, stop speed
  **0.5 m/s**, stop min duration **120 s**.
* Split is **grouped by `traj_uid`** (whole trajectories, never random point
  mixing).
* Full EDA wall time on this machine: **56.5 s**.
* One command: `powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1`
  (see `ml/data/README.md` for the dataset download and the `-Fast` /
  `-Synthetic` options).

### The SYNTHETIC fallback
If the 299 MB dataset is unavailable, the pipeline falls back to the tiny,
clearly-labelled fixture in `ml/data/synthetic_sample/SYNTHETIC_*` (generated by
`ml/data/make_synthetic_fixture.py`). It is **not GeoLife**: every stage prints a
loud SYNTHETIC banner, figures carry a SYNTHETIC caption, and synthetic scored
outputs are written under a `SYNTHETIC/` directory. **Never report synthetic
numbers as dataset results.**

`ml\run_all.ps1` now also runs `har_eda.py` and `history.py` by default; the two
network steps are opt-in via `-Network` (`osm_eda.py`, `weather_eda.py`) and
`-Transport` (`transport_mode.py`).

---

## 6. OpenStreetMap EDA — `ml/osm_eda.py` (real, live data)

The brief's third dataset is OpenStreetMap. It is not a file to download: it is a
live database, so the script fetches a bounded extract and analyses **that**, with
the raw response cached under `ml/data/osm/`.

| Property | Value (measured) |
| --- | --- |
| Endpoint | `https://overpass-api.de/api/interpreter` (POST, 120 s timeout) |
| Fetch time (UTC) | `2026-10-17T13:39:46Z` |
| Bounding box (S,W,N,E) | `39.9019627, 116.3887168, 39.9154373, 116.4062832` (1.5 km × 1.5 km, Tiananmen, Beijing) |
| Nodes / ways / relations | **12,783 / 3,197 / 0** (15,980 elements) |
| Node density | **5,681.4 nodes/km²** |
| Top tag keys | `building` 1,205 · `highway` 1,075 · `height` 800 · `building:part` 583 |
| `highway=*` classes | footway 504 · pedestrian 114 · steps 104 · service 83 (17 distinct) |
| `amenity=*` classes | restaurant 36 · toilets 22 · cafe 12 (22 distinct) |
| Attribution | © OpenStreetMap contributors, ODbL |

Outputs: `ml/outputs/osm/` — six figures, seven tables (including the element
centroids used for the scatter map) and `findings.md` recording the exact query,
endpoint and fetch time. The exact query is reproducible:

```
[out:json][timeout:120];
(
  node(39.9019627,116.3887168,39.9154373,116.4062832);
  way(39.9019627,116.3887168,39.9154373,116.4062832);
);
out center;
```

Re-run with `python ml/osm_eda.py` (uses the cache) or `--refresh`. If every
Overpass mirror fails the script writes `NOT_RUN.md` with the exact error and
exits non-zero — it never fabricates an extract.

## 7. Weather EDA — `ml/weather_eda.py` (provider is stated, never implied)

`DATASETS.pdf` names the **OpenWeatherMap** API, which needs an API key that was
not supplied. The script therefore supports both providers and *says which one it
used on every artefact*:

* if `OPENWEATHER_API_KEY` is set → **OpenWeatherMap**;
* otherwise → the key-free **Open-Meteo** historical archive.

Every artefact in this repository was produced by **Open-Meteo**, and is labelled
as such. It is never presented as OpenWeatherMap data.

| Site | Latitude | Longitude | Days | mean T (°C) | min T | max T | precipitation (mm) | mean RH (%) | harsh days |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `geolife_centroid` | 39.97698 | 116.35008 | 574 | 12.17 | −14.6 | 40.0 | 806.4 | 48.9 | 175 (30.5 %) |
| `huangshan_trek` | 30.13400 | 118.16100 | 574 | 7.07 | −15.9 | 25.5 | 2,676.3 | 73.5 | 218 (38.0 %) |

* Date range: **2011-01-01 … 2012-07-27** (the two sites share it).
* Site A is the **component-wise median** of the 1,429,740-point cleaned GeoLife
  sample (the arithmetic mean is dragged west by long-haul traces); Site B is the
  Huangshan (Yellow Mountain) scenic area, an analyst-chosen trekking deployment
  point — stated as such, **not** derived from GeoLife.
* Harsh-day rule (explicit): precipitation ≥ 10 mm **or** min T ≤ −5 °C **or**
  max T ≥ 35 °C **or** mean wind ≥ 30 km/h.

Outputs: `ml/outputs/weather/` — four figures (daily temperature range, monthly
precipitation, humidity/wind distributions, harsh days per month), four tables and
`findings.md` with the exact request URLs. Re-run with `python ml/weather_eda.py`.

## 8. UCI HAR EDA — `ml/har_eda.py`

The HAR training tables already existed; this adds the missing **figure set** under
`ml/outputs/har/eda/`: class balance (train/test), a subject-split table, per-axis
accelerometer/gyroscope signal bands, gravity-magnitude histograms and a
feature-correlation heatmap. Measured inputs: **561 features**, **7,352** training
windows, **2,947** test windows, **6** activities, and a **subject-disjoint**
split (**21 / 9** subjects, overlap **0**). Overall gravity magnitude **1.0323 g**
(1.0075–1.0516 g per activity).

## 9. Historical trajectory analysis and zone-crossing trends — `ml/history.py`

Real GeoLife data, no labels required.

* Scope: **18,670 trajectories / 182 users**, spanning **2000-01-01 … 2012-07-27**;
  point-level analyses use the cleaned sample (**1,429,740 points / 1,200
  trajectories**, seed 42).
* Distance: **1,021,454 km** total across all users (median 516.9 km/user).
* Temporal: busiest hour **09:00** (108,135 fixes), quietest **21:00** (11,095);
  busiest weekday **Sat**; peak month **2008-11** (1,168 trajectories).
* Revisit/stay: **557 stops**; overall **revisit rate 0.487**; the single
  most-revisited place has **19** stops.
* **Zone-crossing trend.** The zone is derived, not hand-drawn: take the cleaned
  points inside lat [39.5, 40.5], lon [115.5, 117.0], find the densest 0.02° cell
  (**39.9900 N, 116.3300 E**, 99,903 points) and expand it by ±0.03°. Resulting
  zone: **S 39.95, W 116.29, N 40.03, E 116.37**. Of the **785** trajectories that
  intersect it, **431** cross it at least once — **430 entries / 426 exits** over
  58 months, plotted as a monthly trend.

Outputs: `ml/outputs/history/` (figures, tables, `findings.md`).

## 10. Transport-mode model, and the honest answer on "future risk"

`ml/transport_mode.py` trains a supervised model on the **only valid supervised
target in the dataset** — GeoLife `labels.txt` transportation mode — and labels
itself clearly as *next-segment transport-mode prediction, **not** a risk model*.

* **9,584** labelled segments from **69** users; **subject-disjoint** split
  (**45 train / 19 test** users; 8,036 / 1,548 segments).
* Random forest, unseen users: **accuracy 0.8475**, **macro-F1 0.5784**,
  weighted-F1 0.8507. Top features: `p95_speed_mps`, `distance_m`,
  `mean_speed_mps`.

**Future-risk prediction: not built, on purpose.** None of the four datasets
contains an injury, danger or incident outcome label. GeoLife's labels are
transport modes; UCI HAR's are six scripted activities; OSM has no outcomes; the
weather series has no tourist incidents. With no outcome to predict, a
"future-risk" model would be an invention, so none is shipped. The app's
`ApproachRisk` score is a **documented weighted heuristic** over proxemics
(zone severity, distance, dwell, night) — not a learned model — and it is
described as such in `docs/model_card_movement.md` and `README.md` §9.3.

Outputs: `ml/outputs/transport/` (figures, tables, `findings.md`, `model_card.md`).
