# Data, models and evaluation — consolidated evidence report (`ml/`)

*Generated 2026-10-19. Sources of truth: the scripts in `ml/`, the artefacts in `ml/outputs/`, the
JSON/CSV reports the training runs wrote, and the parallel model cards — `docs/dataset_and_eda.md`,
`docs/model_card.md`, `docs/model_card_movement.md`, `docs/future_risk_prediction.md`,
`docs/test_report.md`. Nothing in this document is copied from a proposal, a slide, or a README
claim.*

---

> **Reading this as a non-specialist?** Start with the plain-English companion,
> [`docs/data_ml_explained_simply.md`](data_ml_explained_simply.md) — the same findings in everyday
> words, with the jargon explained. This document is the technical evidence underneath it.

## 0. How to read this document

### 0.1 Evidence policy

This report was written under five self-imposed rules, and every number in it obeys them:

1. **A dataset is "used" only if its files are on disk and were read by a script.** `ml/requirements.txt`,
   an artefact under `ml/outputs/`, or a dataset file under `ml/data/` is evidence. A mention in a
   README is not.
2. **Every metric carries the file it came from.** Where the file is a JSON report produced by a
   training run, the number is quoted exactly as written, not rounded up.
3. **A metric that cannot be computed is marked `NOT AVAILABLE`, with the reason.** It is never filled
   with a plausible-looking value.
4. **A study that was not run is marked `NOT CONDUCTED`.** No user feedback is invented.
5. **Derived numbers are labelled `DERIVED`** and show the arithmetic, so a reader can reproduce them
   from the saved artefacts without re-running anything.

### 0.2 Legend used throughout

| Tag | Meaning |
|---|---|
| *MEASURED* | Written into a saved artefact by a script that ran on real data. |
| *DERIVED* | Arithmetic on saved values, shown inline. Reproducible from the saved files. |
| `NOT AVAILABLE` | The underlying data does not exist or was never saved. Reason given. |
| `NOT CONDUCTED` | The activity was never performed. |
| `N/A` | Not applicable to this model's task or metric (e.g. ROC-AUC for a non-binary unsupervised score). |

### 0.3 What this document is not

It is not a claim that the app can predict danger. Four of the five trained components answer
questions about **movement, activity and laboratory falls**. None of them was trained on a tourist, an
incident, an injury or a crime. That distinction is the subject of section F, and it is repeated there
because it is the single easiest thing to get wrong.

---

## 1. Component inventory at a glance

| Component | Trained? | Model | Artifact | Runs where | The question it answers |
|---|---|---|---|---|---|
| Trajectory anomaly detector | No (unsupervised) | `IsolationForest`, 200 trees | `ml/models/isolation_forest.joblib` (1,753.5 KB), `ml/models/model_meta.json`, `ml/models/split.json` | `ml/` only (offline analysis) | "Is this GPS point's movement statistically unusual?" |
| Activity recognition | **Yes** | `RandomForestClassifier`, 32 trees, depth 8, 24 features | `app/src/main/assets/har_activity_model.json` (268,734 bytes) | **On the phone**, offline | "Walking / upstairs / downstairs / sitting / standing / lying?" |
| Impact (fall-signature) detector | **Yes** | `RandomForestClassifier`, 40 trees, 24 features | `app/src/main/assets/har_fall_model.json` (59,679 bytes) | **On the phone**, offline | "Does this window contain the speeded-up signature of a hard impact?" |
| Future-risk predictor | **Yes** | `RandomForestClassifier`, 120 trees, depth 11, 22 features + Platt calibration | `app/src/main/assets/future_risk_model.json` (1,974,297 bytes) | **On the phone**, offline | "Given the last second of accelerometer data, will a hard impact land within the next second?" |
| Transport-mode classifier | **Yes** | `RandomForestClassifier`, 300 trees, 9 features | `ml/models`/`ml/outputs/transport/` (analysis only) | `ml/` only | "Which transport mode is this labelled segment?" — **explicitly not a risk model** |
| Geographic risk (geofence) | **No — configured rule** | Polygon test + event log | `app/` + `backend/` (`zones`, `events`) | Phone + server | "Is the tourist inside a restricted polygon?" |
| Approach-risk score | **No — documented heuristic** | Weighted checklist (`ApproachRiskModel`) | `app/` | Phone | "How concerning is an approach, by published weights?" |

Full separation of these concepts, including what must never be conflated, is in **section F**.

---

## A. Dataset documentation

### A.1 Inventory

| Dataset | Source / official URL | Version | Licence | Obtained here? | Used for |
|---|---|---|---|---|---|
| **GeoLife GPS Trajectories 1.3** | Microsoft Research Asia — https://www.microsoft.com/en-us/download/details.aspx?id=52367 | 1.3 (2012-08-01) | No OSI licence bundled; citation-only, research use | **Yes** — 313,164,406 bytes, unzipped | EDA, unsupervised anomaly detection, transport-mode model, historical/zone analysis |
| **UCI HAR** (Human Activity Recognition Using Smartphones) | UCI ML Repository, dataset id `uci-240` | 1.0 | Not documented in this repository; see the UCI dataset page | **Yes** | EDA, activity recognition (shipped), impact detector (shipped) |
| **UniMiB SHAR** | Micucci, Mobilio, Napoletano (2017), arXiv:1611.07688v2 — University of Milano-Bicocca | as published 2017 | Publicly distributed for academic research; **no explicit OSI licence** | **Yes** — archive 266,206,080 bytes, SHA-256 `365a3967…67160` | Future-risk model (shipped) |
| **OpenStreetMap** (Overpass extract) | https://overpass-api.de/api/interpreter | live DB, fetched 2026-10-17T13:39:46Z | © OpenStreetMap contributors, **ODbL** | **Yes** — 15,980 elements cached | Map-context EDA only. Never a training input |
| **Weather** (Open-Meteo historical archive) | https://archive-api.open-meteo.com/v1/archive | live archive, 2011-01-01…2012-07-27 | Open-Meteo free/open archive | **Yes** — cached JSON per site | Context EDA only. Never a training input |
| **Synthetic fixture** | `ml/data/make_synthetic_fixture.py` | n/a | project-generated | **Yes** (fallback) | Pipeline smoke-test only; every output is banner-labelled SYNTHETIC |

> **OpenWeatherMap was never used.** `DATASETS.pdf` names it, but no API key existed, so
> `ml/weather_eda.py` fell back to the key-free **Open-Meteo** archive and labels every artefact with
> the provider it actually used. See `ml/outputs/weather/findings.md`.

**Access dates:** all local dataset files were read during the runs recorded in `ml/outputs/*/findings.md`;
the two live fetches carry their own UTC timestamps (OSM `2026-10-17T13:39:46Z`; weather range fixed by
the query, cached 2026-10-17).

### A.2 GeoLife GPS Trajectories 1.3

| Item | Value (*MEASURED*) |
|---|---|
| Archive | 313,164,406 bytes (298.7 MB) |
| User directories | **182** |
| `.plt` trajectory files | **18,670** (the bundled user guide claims 17,621 — **both are reported**) |
| `labels.txt` transport-mode files | **69** |
| Data lines in the whole archive | **24,876,978** |
| Format | ASCII, 6 header lines + CRLF, then 7 comma-separated tokens per line: `lat, lon, 0, altitude, days_since_1899-12-30, YYYY-MM-DD, HH:MM:SS` |
| Schema verification | 60 sampled files → all 6 header lines, 0 malformed; full scan → **24,876,978 rows parsed, 0 malformed**, two independent time encodings agree to within 4.79×10⁻⁶ s |
| Units | WGS-84 degrees; `altitude` declared *feet* but **unreliable** (flagged, never relied upon) |
| Sampling rate | **Irregular.** median `dt` = 2.00 s, mean 4.74 s, p95 5.00 s, max 3,595 s; 96.3 % of intervals in the 1–5 s band |
| Target labels | **None for safety.** 14,718 transport-mode segment labels across 69 users only |
| Real / simulated | **Real-world**, volunteer-collected 2000-01-01 … 2012-07-27, overwhelmingly Beijing |
| Collection method | Volunteers carried GPS loggers/phones for multi-year periods |

**Why it was selected:** it is the only real, large, public GPS trajectory corpus available, and it
supports the unsupervised anomaly work plus every geospatial trend analysis. It supports **no**
supervised risk model, because it contains no incident or danger outcome.

**Data-quality problems found and handled:**

- **698,900** duplicate `(trajectory, timestamp)` rows removed; **1** invalid-coordinate row; **0**
  unparsable timestamps; **0** malformed rows.
- A **critical quirk**: `.plt` file names collide across users — **847** trajectory ids are shared by
  more than one user. Every grouping therefore uses the composite key `traj_uid` (user id joined with
  the file stem). Grouping on the bare file name would silently merge different people's trajectories
  — a leakage bug. Enforced in `ml/common.py::add_traj_uid`.
- GPS jumps: of 1,423,352 per-step speeds, **4,525** exceed 60 m/s and are **excluded from the
  statistics** (flagged, not deleted from the data).
- Point-level analyses run on a fixed-seed sample (1,429,740 points / 1,200 trajectories); length and
  duration analyses use all 18,670 trajectories.
- No class imbalance applies (there are no classes).

**Privacy:** the data is human-mobility data from named volunteers, published by Microsoft for
research. This project stores none of it in the app or the backend, and redistributes none of it.

**Limitations:** Beijing-centric, 2007–2012, volunteers with loggers, no incident outcomes, unreliable
altitude, irregular sampling, and a documented dispute between the bundled guide's trajectory count
and the archive's.

### A.3 UCI HAR

| Item | Value (*MEASURED*) |
|---|---|
| Windows | 7,352 train / 2,947 test |
| Window length | 128 samples @ 50 Hz = **2.56 s** |
| Raw channels | 9 (`total_acc_x/y/z`, `body_acc_x/y/z`, `body_gyro_x/y/z`) |
| Feature vectors | **561** per window, names from the dataset's own `features.txt` |
| Activities | 6: WALKING, WALKING_UPSTAIRS, WALKING_DOWNSTAIRS, SITTING, STANDING, LAYING |
| Subjects | 30 (ages 19–48); **21 train / 9 test, overlap 0** |
| Units | accelerometer in g; gyroscope in rad/s |
| Real / simulated | **Real recordings**, scripted protocol, waist-mounted phone |
| Integrity checks | `train_non_finite = 0`, `test_non_finite = 0`, `raw_windows_match_features = true`, `feature_vectors_match_names = true` |
| Gravity sanity | mean total-acceleration magnitude **1.0323 g** (train) / **1.0328 g** (test) — a real measurement centring on gravity |
| Other recorded values | `body_gyro_train_rms = 0.4068`; `total_acc_x_train_min_max = [-0.4666, 2.1976]` |

**Why selected:** it is the standard, real, subject-disjoint benchmark for phone-based activity
recognition, and it is the only dataset here with **real human activity labels** — which is exactly
what the shipped activity model needs.

**Class balance (real counts):**

| Activity | Train | Test |
|---|---:|---:|
| WALKING | 1,226 | 496 |
| WALKING_UPSTAIRS | 1,073 | 471 |
| WALKING_DOWNSTAIRS | 986 | 420 |
| SITTING | 1,286 | 491 |
| STANDING | 1,374 | 532 |
| LAYING | 1,407 | 537 |

The classes are near-balanced, so accuracy is meaningful here — unlike the future-risk task.

**Missing / duplicates / outliers:** none recorded; `har_dataset_meta.json` reports zero non-finite
values on both sides. The dataset ships pre-segmented, pre-filtered windows, so no additional
cleaning was applied beyond integrity verification.

**Preprocessing:** windowing, filtering and gravity separation are already done by the dataset
authors. This project adds units conversion (m/s² → g, dividing by 9.80665) so the phone's live
features match the training table.

**Split methodology:** the dataset's **official** split, which is subject-disjoint. This matters
because windows from one person's recording are highly correlated; a random window split would leak
identity into the test set.

**Limitations:** 30 volunteers in a lab performing scripted activities with a **waist-mounted**
phone; not tourists, not outdoors, not with bags, and **no falls** whatsoever. It cannot validate
real-world performance, and it cannot support a fall-detection claim (see A.3.1).

#### A.3.1 The fall problem inside UCI HAR

UCI HAR **contains no falls.** There is no fall class, no impact annotation and no subject who fell.
The impact detector's positive class was therefore **manufactured**: a fall-like transient (a short
free-fall dip, an impact spike of several g, and the stillness that follows) was injected into real
recorded windows from the held-out subjects; the negatives are those same windows untouched. Every
metric for that model therefore measures detection of the **injected signature**, and is reported as
such everywhere.

### A.4 UniMiB SHAR

| Item | Value (*MEASURED*) |
|---|---|
| Citation | Micucci, D., Mobilio, M., Napoletano, P. (2017). *UniMiB SHAR: A Dataset for Human Activity Recognition Using Acceleration Data from Smartphones.* arXiv:1611.07688v2 |
| Archive | `ml/data/unimib/UniMiB-SHAR.zip`, 266,206,080 bytes, SHA-256 `365a396729c0755b4ba8ef8ee0ba52a0621aa535282470546d8a663ff2567160` |
| Source file used | `data/full_data.mat`, 29,084,022 bytes, SHA-256 `087035da94d6f77efd005d6d2bedd2f959e40f70a0524deb0edb0c463fce5aa8` |
| Subjects | **30** (gender/age/height/weight recorded; two subjects' height/weight fields unpopulated — treated as unreadable, never imputed) |
| Activities | 9 of daily living + 8 fall types |
| Trials read / used | 1,980 / **1,979** |
| Sampling rate | **50 Hz** |
| Units | g (**total** acceleration, gravity included) |
| Real / simulated | **Real recordings of real falls** — a lab protocol, but genuine falls by genuine volunteers |
| ADL / fall trial counts | 539 ADL, 1,440 fall trials used |
| Impact magnitude range | **2.0299 g … 3.4562 g** |

**Why `full_data.mat` and not the shipped windows:** UniMiB also ships pre-cut 151-sample windows,
but they are **centred on the impact peak**, which would destroy the pre-impact timeline the label is
built from. The pipeline therefore reads the full recordings and cuts its own windows.

**The defect that was found and reported:** one recording is corrupt — **subject 10, `Jumping`,
trial 1** contains **9,369 non-finite values out of 9,624** (3,208 samples). It was **dropped,
counted and reported** in `dataset_provenance.json` (`data_quality.dropped`), not interpolated and not
silently skipped. A NaN would have propagated into every tree that touched it. This is why the kept
count is 1,979 and `Jumping` shows 59 trials rather than 60.

**Six fall trials** never reach the 2.0 g impact threshold. They are excluded from the positives and
counted in `window_stats.n_fall_trials_without_impact`.

**Licence position, stated as it is:** publicly distributed for academic research; **no explicit
OSI-approved licence**. No licence is asserted in the provenance file. Only derived artefacts are
redistributed here — model weights, aggregate metrics and 16 reference windows — **never the raw
recordings**.

**Limitations:** 30 healthy adults performing scripted movements in a laboratory, phone carried or
pocketed; nothing from a tourist, outdoors, in weather, in a bag, or in a moving vehicle. The label is
a *fall impact*, not a tourist incident.

### A.5 OpenStreetMap (Overpass extract)

| Item | Value (*MEASURED*) |
|---|---|
| Endpoint | `https://overpass-api.de/api/interpreter` (POST, 120 s timeout) |
| Fetch time (UTC) | `2026-10-17T13:39:46Z` |
| Bounding box (S,W,N,E) | `39.9019627, 116.3887168, 39.9154373, 116.4062832` — 1.5 km × 1.5 km centred on Tiananmen, Beijing |
| Box area | 2.2500 km² |
| Elements | nodes **12,783**, ways **3,197**, relations **0** (total **15,980**) |
| Density | **5,681.4 nodes/km²**; 7,102.3 elements/km² |
| Attribution | © OpenStreetMap contributors, **ODbL** |
| Real / collected | **Real, live public map database**, fetched (not simulated) |

**Why selected:** the brief names OpenStreetMap as the map substrate, and the app's map fallback uses
OSM tiles. The EDA characterises what a tourist-area extract actually contains (footways, steps,
amenities, buildings) to sanity-check the mapping feature.

**Use:** **EDA only.** Not a training input for any model. The raw Overpass response is cached; if
every mirror fails the script writes `NOT_RUN.md` with the exact error and exits non-zero — it never
fabricates an extract.

### A.6 Weather (Open-Meteo)

| Site | Lat | Lon | Days | mean T (°C) | min T | max T | precip (mm) | RH (%) | wind (km/h) | Harsh days |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `geolife_centroid` | 39.97698 | 116.35008 | 574 | 12.17 | −14.6 | 40.0 | 806.4 | 48.9 | 7.36 | 175 (30.49 %) |
| `huangshan_trek` | 30.13400 | 118.16100 | 574 | 7.07 | −15.9 | 25.5 | 2,676.3 | 73.5 | 6.56 | 218 (37.98 %) |

| Item | Value (*MEASURED*) |
|---|---|
| Provider actually used | **Open-Meteo** historical archive (key-free) — **not** OpenWeatherMap |
| Date range | 2011-01-01 … 2012-07-27 (UTC), 574 days, shared by both sites |
| Site A definition | **component-wise median** of the 1,429,740-point cleaned GeoLife sample (the arithmetic mean is dragged west by long-haul traces: 39.49793 N, 112.03847 E) |
| Site B definition | Huangshan (Yellow Mountain) Scenic Area — an **analyst-chosen trekking deployment point, not derived from GeoLife** |
| Harsh-day rule (explicit) | precipitation ≥ 10 mm **or** min T ≤ −5 °C **or** max T ≥ 35 °C **or** mean wind ≥ 30 km/h |
| Harsh-day breakdown | geolife: rain 17, cold 133, heat 25, wind 0 · huangshan: rain 84, cold 141, heat 0, wind 0 |
| Use | **EDA / context only.** No model consumes it |

**Limitation:** a 574-day window for two points is context, not evidence about any tourist. Weather is
in the app as information, never as a model input.

### A.7 Synthetic fixture

`ml/data/synthetic_sample/SYNTHETIC_*` (built by `ml/data/make_synthetic_fixture.py`) exists so the
pipeline can be smoke-tested when the 299 MB archive is absent. Every stage prints a loud SYNTHETIC
banner, figures carry a SYNTHETIC caption, and scored outputs go under a `SYNTHETIC/` directory with
a `.SYNTHETIC_WARNING.txt` beside them (0.2 KB, *MEASURED* present). **No synthetic number is reported
as a dataset result anywhere in this document.**

### A.8 Dataset → model / feature mapping

| Dataset | Unsupervised anomaly model | Activity model | Impact detector | Future-risk model | Transport model | EDA / trends | Map context |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| GeoLife 1.3 | **✔ train + test** | – | – | – | **✔ labels only** (`labels.txt`) | **✔** | **✔** |
| UCI HAR | – | **✔ train + test** | **✔ train + test** | – | – | **✔** | – |
| UniMiB SHAR | – | – | – | **✔ train + test** | – | via training pipeline | – |
| OpenStreetMap | – | – | – | – | – | **✔** | **✔** |
| Open-Meteo weather | – | – | – | – | – | **✔** | – |
| Synthetic fixture | smoke test only | – | – | – | – | – | – |

### A.9 Datasets considered and **not** used

- **OpenWeatherMap** — named in `DATASETS.pdf`; **never queried** (no API key). Open-Meteo used
  instead, and the provider is stated on every artefact.
- **SisFall, MobiFall** (*named, not downloaded*) — public datasets containing genuine falls from real
  volunteers. `docs/model_card_movement.md` §5 and this report name them as the upgrade path that
  would turn the impact detector into a fall-detection claim. **It has not been done.**
- **Any tourist-incident / injury / crime dataset** — none exists in this project, and none was
  obtained. This is the reason no supervised tourist-risk model exists at all (section F).

---

## B. Exploratory data analysis

### B.0 EDA output inventory

| Stage | Figures | Tables | Findings file |
|---|---:|---:|---|
| GeoLife EDA (`ml/eda.py`) | 12 | 11 | `ml/outputs/eda/findings.md` |
| UCI HAR EDA (`ml/har_eda.py`) | 6 | 5 | `ml/outputs/har/eda/findings.md` |
| Transport-mode EDA (`ml/transport_mode.py`) | 3 | 4 | `ml/outputs/transport/findings.md` |
| OSM EDA (`ml/osm_eda.py`) | 6 | 7 | `ml/outputs/osm/findings.md` |
| Weather EDA (`ml/weather_eda.py`) | 4 | 4 (+summary) | `ml/outputs/weather/findings.md` |
| Historical / zone trend (`ml/history.py`) | 6 | 10 | `ml/outputs/history/findings.md` |
| Anomaly evaluation (`ml/evaluate.py`) | – | 2 | `ml/outputs/eval/findings.md` |

Everything below is real GeoLife/HAR/UniMiB/OSM/weather data. Point-level GeoLife analyses use the
fixed-seed 1,200-trajectory sample (1,429,740 points); length/duration and per-user analyses use all
18,670 trajectories.

### B.1 GeoLife EDA — the twelve required items

**1. Shape / columns / dtypes.** Cleaned point sample = **1,429,740 rows × 15 columns**
(`table_01_schema.csv`); trajectory summary = **18,670 rows × 22 columns**.
*What it shows:* the sample is large enough for stable distributions and the summary covers every
trajectory. *Influence:* point-level statistics are quoted as sample statistics; length/duration as
whole-corpus statistics.

**2. Missing values and duplicates.** Across 24,876,978 raw rows: 698,900 duplicate-timestamp rows,
1 invalid coordinate, 0 unparsable timestamps, 0 malformed rows. In the cleaned sample, NaN occurs
only in derived columns at segment starts — `dt_s` missing = **1,200** rows, exactly one per
trajectory.
*What it shows:* the missingness is **structural** (first point of each trajectory has no previous
point), not random. *Influence:* nothing was imputed; feature code drops the first point of each
segment rather than filling it.

**3. Spatial extent.** Latitude **1.27681 … 51.10710**; longitude **−179.69134 … 179.99330**
(`fig_01_latlon_scatter.png`, `fig_02_latlon_density.png`). Density is overwhelmingly Beijing, with a
scatter of long-haul trips.
*Influence:* any "typical movement" statement is a Beijing statement. The weather site A uses the
**median** of the sample rather than the mean for exactly this reason.

**4. Trajectory length and duration (all 18,670).** Points/trajectory: median **504**, mean
1,295.0, max **92,645** (`fig_03`). Duration: median **2,528 s**, mean 9,675 s, max 348,431 s
(`fig_04`). Summed trajectory distance **1,021,454,302 m ≈ 1,021,454 km**.
*What it shows:* heavy right tails — a few very long recordings dominate totals.
*Influence:* windowed features (`*_w5`) were chosen over per-trajectory aggregates so a single long
recording cannot dominate; the model trains on points, not on whole trajectories.

**5. Sampling interval.** median `dt` = **2.00 s**, mean 4.74 s, p95 5.00 s, max 3,595 s; **96.3 %**
of intervals fall in the dense 1–5 s band (`fig_05`).
*What it shows:* the sensor is deliberately duty-cycled and occasionally gap-ridden.
*Influence:* the gap threshold for splitting trajectories is **120 s**, and `log_dt_s` was made a
model feature precisely because `dt` is so variable.

**6. Speed (GPS jumps handled).** **4,525** of 1,423,352 per-step speeds exceed the 60 m/s jump
threshold and are **excluded** from the statistics. Speed excluding jumps: mean **6.55 m/s**, median
**3.23 m/s**, p95 20.72 m/s, max 59.99 m/s (`fig_06`).
*What it shows:* raw speeds contain physically impossible outliers — GPS artefacts, not people.
*Influence:* the jump flag is kept as a *feature of the data* (and is what the injection evaluation
mimics), never silently deleted; the anomaly model learns on the raw movement signal and the anomaly
evaluation injects **teleports of the same family** the data already contains.

**7. Dwell time / stops.** **557** stops detected (speed < 0.5 m/s sustained ≥ 120 s). Stop duration:
median **174 s**, mean 360 s, max 9,945 s (`fig_07`).
*Influence:* the app's "dwell" concept for approach risk reuses the same 0.5 m/s / 120 s definition,
so the heuristic and the analysis agree on what a stop is.

**8. Time-of-day patterns.** Busiest hour **09:00** with **108,135** fixes; quietest **21:00** with
**11,095** (`fig_08`). Timestamps are **local clock time — GeoLife stores no timezone**.
*What it shows:* commuting-shaped daily rhythm. *Influence:* `tod_sin`/`tod_cos` are model features,
and the fact that **no timezone is stored** is recorded as a limitation rather than papered over.

**9. Spatial trajectories plot.** 60 sample trajectories drawn
(`fig_09_spatial_trajectories.png`).

**10. Outlier analysis.** IQR-flagging per engineered feature (top five share of rows flagged):
`alt_change` **679,868 (47.55 %)**, `straightness_w5` 212,483 (14.86 %), `accel_mps2` 192,362
(13.45 %), `turn_deg` 175,317 (12.26 %), `altitude` 160,312 (11.21 %)
(`table_08_outlier_summary.csv`, `fig_10_feature_boxplots.png`).
*What it shows:* the largest outlier burden sits in **`alt_change` and `altitude` — the known-unreliable
field**. *Influence:* both stay in the feature list (they are legitimate signals when present) but the
documentation flags the altitude unreliability, and no conclusion anywhere rests on altitude alone.

**11. Correlation heatmap.** Strongest correlation: `speed_mps ~ speed_mean_w5` at **r = 0.979**
(`table_09_correlation.csv`, `fig_11_correlation_heatmap.png`).
*What it shows:* the two measure the same motion over different windows — expected, not a problem.
*Influence:* for a *supervised* model this would be a candidate de-duplication; for `IsolationForest`
(distance-like scoring) mild redundancy is harmless, so both were kept and the duplicate is documented
rather than hidden.

**12. Class balance / labels.** `labels.txt` exists for **69 users** with **14,718** labelled
segments: `walk` 6,460 · `bus` 2,853 · `bike` 2,089 · `taxi` 1,179 · `car` 993 · `subway` 813 ·
`train` 299 · `airplane` 17 · `boat` 7 · `run` 6 · `motorcycle` 2
(`table_10_transport_labels.csv`, `fig_12_transport_mode_balance.png`).
*What it shows:* **severe** imbalance (walk 6,460 vs motorcycle 2).
*Influence:* the transport model uses `class_weight="balanced_subsample"`, and the classes with 0–1
test examples are reported with their support rather than averaged away (that is why macro-F1 is
0.5784 while accuracy is 0.8475).

> **There are NO danger/anomaly labels in GeoLife.** Class balance for a danger-detection task is
> therefore `NOT AVAILABLE` — the task has no classes. This is why the trajectory model is
> unsupervised and its output is a *movement anomaly score*, never a danger probability.

### B.2 UCI HAR EDA

- **Shape:** 561 features per window; 7,352 train / 2,947 test windows; 6 activities; raw window 128
  samples @ 50 Hz (2.56 s), 9 inertial channels.
- **Subject split (leakage safety):** train subjects 21, test subjects 9, **overlap 0** → genuinely
  subject-disjoint (`fig_har_eda_02_subject_split.png`).
- **Class balance:** near-uniform (see A.3). *Influence:* accuracy is a legitimate headline here, and
  no resampling or class weighting was needed.
- **Gravity magnitude:** overall mean **1.0323 g**; per-activity means 1.0075 … 1.0516 g
  (`fig_har_eda_05_gravity_magnitude.png`). *What it shows:* real accelerometer data centred on
  gravity, not a constant or a synthetic field. *Influence:* this sanity check is what justifies
  dividing live m/s² samples by 9.80665 to match the dataset.
- **Signal bands:** per-axis accelerometer and gyroscope bands per activity
  (`fig_har_eda_03_acc_signal_bands.png`, `fig_har_eda_04_gyro_signal_bands.png`).
  *What it shows:* walking variants overlap heavily; sitting/standing/lying separate. *Influence:*
  identical to the eventual confusion pattern — the model confuses the three walking variants and
  nothing else. The UI therefore groups the three as "ambulating" and never acts on the distinction.
- **Feature correlation:** a fixed subset (every 14th feature) heatmap
  (`fig_har_eda_06_feature_correlation.png`). *What it shows:* the 561 features are heavily redundant.
  *Influence:* supports the 24-feature time-domain subset — the measured accuracy-vs-features curve
  shows the curve flattening past ~50 features (B.7).

### B.3 UniMiB SHAR EDA (via the future-risk pipeline)

This dataset's "EDA" is the window/label construction, because that is what determines everything
downstream:

| Quantity | Value (*MEASURED*, `future_risk/metrics.json`) |
|---|---|
| Trials in / kept | 1,980 / **1,979** (1 corrupt recording dropped) |
| Fall trials used | 1,440 (6 excluded — never reached 2.0 g) |
| ADL trials | 539 |
| Windows cut | **51,128** (50-sample window, 10-sample stride) |
| Positive windows | **6,458** → positive rate **12.63 %** |
| Impact peak range | **2.0299 g … 3.4562 g** |
| Subjects | 30 |
| Lead-time histogram (windows per lead) | 50 samples: 923 · 40: 1,268 · 30: 1,412 · 20: 1,425 · 10: 1,430 |

**Class imbalance is real and severe** (12.63 % positive). *Influence:* `class_weight="balanced_subsample"`;
every reported table carries precision, recall and **PR-AUC**, because accuracy alone would be
misleading (a "always no" model already scores 87.1 %). The PR-AUC baseline (0.128565) *is* the
positive rate, and that identity is stated in the model card.

**Window geometry was measured, not chosen:** fall trials run 151–446 samples (median 174 = 3.5 s),
with the impact near index ~120, leaving ≥ 95 samples of pre-impact signal. A 50-sample (1.0 s) window
ending at least 1 sample before impact fits in **every** included trial — so **1.0 s is the longest
fully supported horizon**, not a round number. The shortest observed lead is 10 samples (0.2 s) and
the longest 50 samples (1.0 s).

### B.4 OpenStreetMap EDA

- Top tag keys: `building` 1,205 · `highway` 1,075 · `height` 800 · `building:part` 583 ·
  `roof:material` 542.
- `highway=*` — **17 distinct classes**: footway 504 · pedestrian 114 · steps 104 · service 83 ·
  crossing 63 · street_lamp 62.
- `amenity=*` — **22 distinct**: restaurant 36 · toilets 22 · cafe 12 · parking 9.
- `building=*` — **13 distinct**: `yes` 1,160 · wall 18 · gatehouse 7.
- *What it shows:* a dense pedestrianised tourist area is dominated by footways, steps and
  amenities — i.e. exactly the terrain where a "restricted zone" polygon and a walking-speed model
  are meaningful.
- *Influence:* it informed the app's map-context feature (surrounding amenities are informational)
  and confirmed that OSM is usable as the keyless basemap fallback. **No model uses OSM features.**

### B.5 Weather EDA

Summarised in A.6. The analysis established an explicit, reproducible harsh-day rule and showed that
the two candidate sites have very different profiles (`geolife_centroid` cold- and heat-heavy;
`huangshan_trek` rain-heavy with 2,676 mm over 574 days). *Influence:* weather is presented to the
tourist as context and is **not** an input to any model — a fact stated in the app and in section F.

### B.6 Historical trajectory and zone-crossing EDA

| Quantity | Value (*MEASURED*, `history/findings.md`) |
|---|---|
| Scope | 18,670 trajectories / **182** users, 2000-01-01 23:12:19 … 2012-07-27 08:07:00 |
| Point sample | 1,429,740 points / 1,200 trajectories (seed 42) |
| Trajectories per user | median **28**, max 2,153 |
| Total distance | **1,021,454 km** across all users (median 516.9 km/user) |
| Active days per user | median 19, max 1,245 |
| Speed | median of user medians **2.07 m/s**; p95 of user p95s 13.12 m/s |
| Busiest hour / weekday / month | **09:00** (108,135 fixes) / **Sat** / **2008-11** (1,168 trajectories), 65 months |
| Stops / revisit rate | **557** stops; overall revisit rate **0.487**; most-revisited single place **19** stops |
| Zone definition (derived, not hand-drawn) | cleaned points in lat [39.5, 40.5], lon [115.5, 117.0] → densest 0.02° cell (**39.9900 N, 116.3300 E**, 99,903 points) expanded ±0.03° → zone S 39.95, W 116.29, N 40.03, E 116.37 |
| Zone crossings | 785 trajectories intersect; **431** cross at least once; **430 entries / 426 exits** over 58 months |

*What it shows:* real people repeatedly visit the same small set of places, and real geofence-style
boundary crossings are common and countable. *Influence:* it grounds the geofence and revisit features
in measured behaviour rather than assumption — and it is the **only** evidence in this project that
the zone-crossing concept is realistic.

### B.7 What the EDA actually changed

| EDA finding | Consequent change |
|---|---|
| `.plt` names collide across users (847) | composite `traj_uid` grouping everywhere — prevents silent cross-user leakage |
| Irregular, gap-ridden sampling (dt 1–3,595 s) | 120 s segment split; `log_dt_s` as a feature; no fixed-rate resampling |
| GPS jumps (4,525 speeds > 60 m/s) | jump flag kept as data property; injection evaluation mimics that same artefact family |
| `altitude` unreliable (47.55 % IQR-flagged) | documented as unreliable; no conclusion depends on it |
| Duplicated motion features (r = 0.979) | documented, not hidden; kept because harmless for the unsupervised scorer |
| Structural missingness (1,200 `dt_s`) | first point of each segment dropped, never imputed |
| UCI HAR near-balanced classes | accuracy used as headline; no resampling |
| UCI HAR walking variants overlap | on-device feature set keeps real labels but the app groups the three as "ambulating" |
| 561 HAR features are redundant | measurable flattening past ~50 features → 24-feature on-device subset accepted with a measured 0.0936 accuracy cost (recorded, not hidden) |
| UniMiB 12.63 % positive rate | `balanced_subsample`; PR-AUC reported alongside accuracy everywhere |
| UniMiB impact always ≥ 2.0 g | 2.0 g reused as the shared "hard landing" threshold in `FallPolicy` and in the label rule |
| GeoLife has no danger labels | the trajectory model is **unsupervised** and its score is never called a probability |
| GeoLife labels are transport modes with 2-example classes | transport model uses balanced class weights; per-class support always printed |

### B.8 Recommendations for future analysis (clearly **not** findings)

These were **not** performed and are listed as future work only:

1. Cross-validated (repeated, multi-fold) subject-disjoint evaluation of the HAR and future-risk
   models — currently each is evaluated on **one** held-out subject fold.
2. A regional GeoLife-like corpus for the deployment area before any real use of the anomaly model.
3. A phone-drop / phone-in-bag negative class (see C.8 and D.4 limitations).
4. Weather/humidity/altitude as *contextual* covariates — explicitly not model inputs today.

---

## B.9 EDA figure gallery — what each chart shows, what it changed

Every chart below is a real file under `ml/outputs/`, produced by the scripts named in B.0. Each entry
gives the **observed result** and the **decision it influenced**. Nothing here is illustrative or
hand-drawn.

**Spatial extent and density** — `fig_01_latlon_scatter.png`, `fig_02_latlon_density.png`

![GeoLife lat/lon scatter](../ml/outputs/eda/fig_01_latlon_scatter.png)
![GeoLife lat/lon density](../ml/outputs/eda/fig_02_latlon_density.png)

- *Shows:* every cleaned sample point on the globe, then as a density surface.
- *Observed:* a dense Beijing cluster plus a scatter of long-haul trips spanning lat 1.28–51.11, lon
  −179.69–179.99.
- *Influenced:* every "typical movement" statement is scoped to Beijing; the weather reference site
  uses the **component-wise median** because the mean is dragged west by those long-haul traces.

**Trajectory length and duration** — `fig_03_traj_length_hist.png`, `fig_04_traj_duration_hist.png`

![Trajectory length histogram](../ml/outputs/eda/fig_03_traj_length_hist.png)
![Trajectory duration histogram](../ml/outputs/eda/fig_04_traj_duration_hist.png)

- *Shows:* points per trajectory and seconds per trajectory, over all 18,670 trajectories.
- *Observed:* heavy right tails — median 504 points / 2,528 s, but max 92,645 points / 348,431 s.
- *Influenced:* windowed (`*_w5`) features were chosen over per-trajectory aggregates, so one very long
  recording cannot dominate the anomaly model; training is at point level, not trajectory level.

**Sampling interval** — `fig_05_sampling_interval_hist.png`

![Sampling interval histogram](../ml/outputs/eda/fig_05_sampling_interval_hist.png)

- *Shows:* the distribution of seconds between consecutive fixes.
- *Observed:* a dense 1–5 s band holding 96.3 % of intervals (median 2.00 s), with a tail to 3,595 s.
- *Influenced:* the 120 s trajectory-split gap, and `log_dt_s` as a model feature rather than any
  attempt to resample to a fixed rate.

**Speed, with GPS jumps excluded** — `fig_06_speed_hist.png`

![Speed histogram](../ml/outputs/eda/fig_06_speed_hist.png)

- *Shows:* per-step speed after the 4,525 physically impossible values (> 60 m/s) are removed.
- *Observed:* mean 6.55 m/s, median 3.23 m/s, p95 20.72 m/s — a plausible human range.
- *Influenced:* jumps are *flagged and kept as a data property*, and the anomaly evaluation injects
  **teleports of that same family**, so the model is tested on the artefact class the data actually has.

**Dwell / stops** — `fig_07_dwell_stops.png`

![Dwell stops](../ml/outputs/eda/fig_07_dwell_stops.png)

- *Shows:* duration of the 557 detected stops (speed < 0.5 m/s sustained ≥ 120 s).
- *Observed:* median 174 s, mean 360 s, max 9,945 s.
- *Influenced:* the app's approach-risk heuristic reuses the identical 0.5 m/s / 120 s stop definition,
  so the heuristic and the analysis agree on what a stop is.

**Time-of-day rhythm** — `fig_08_time_of_day.png`

![Time of day](../ml/outputs/eda/fig_08_time_of_day.png)

- *Shows:* fixes per hour of day.
- *Observed:* a commuting shape — busiest 09:00 (108,135 fixes), quietest 21:00 (11,095).
- *Influenced:* `tod_sin`/`tod_cos` are features in both the anomaly and transport models; the absence
  of any stored timezone is recorded as a limitation rather than silently assumed away.

**Spatial trajectories** — `fig_09_spatial_trajectories.png`

![Sample trajectories](../ml/outputs/eda/fig_09_spatial_trajectories.png)

- *Shows:* 60 sample trajectories drawn on the map, for a human check that the geometry is sane.
- *Observed:* clean, continuous street-level traces, with visible GPS-jump spikes.
- *Influenced:* confirms the cleaning pipeline did not distort real movement, and visualises the
  artefact class the anomaly detector targets.

**Outlier structure** — `fig_10_feature_boxplots.png`

![Feature boxplots](../ml/outputs/eda/fig_10_feature_boxplots.png)

- *Shows:* IQR box plots for every engineered feature.
- *Observed:* the largest outlier burden sits in `alt_change` (47.55 % flagged) and `altitude`
  (11.21 %) — the field the user guide declares in feet and which is visibly unreliable.
- *Influenced:* altitude is documented as unreliable and **no conclusion rests on it**, though both
  stay in the feature list as legitimate signals when present.

**Correlation structure** — `fig_11_correlation_heatmap.png`

![Correlation heatmap](../ml/outputs/eda/fig_11_correlation_heatmap.png)

- *Shows:* pairwise correlation between engineered movement features.
- *Observed:* the strongest pair is `speed_mps ~ speed_mean_w5` at **r = 0.979** — they measure the same
  motion over different windows.
- *Influenced:* for a distance-like unsupervised scorer the redundancy is harmless, so both were kept
  and the duplication is **documented rather than hidden**. It would be a de-duplication candidate for a
  supervised model.

**Transport-mode class balance** — `fig_12_transport_mode_balance.png`

![Transport mode balance](../ml/outputs/eda/fig_12_transport_mode_balance.png)

- *Shows:* labelled segments per transportation mode across the 69 labelled users.
- *Observed:* severe imbalance — `walk` 6,460 down to `motorcycle` 2.
- *Influenced:* `class_weight="balanced_subsample"` in the transport model, and the rule that per-class
  support is always printed beside any macro average.

**UCI HAR class balance and subject split** — `fig_har_eda_01_class_balance.png`,
`fig_har_eda_02_subject_split.png`

![HAR class balance](../ml/outputs/har/eda/fig_har_eda_01_class_balance.png)
![HAR subject split](../ml/outputs/har/eda/fig_har_eda_02_subject_split.png)

- *Shows:* windows per activity on each side, and which subjects went to train versus test.
- *Observed:* near-uniform classes (496–537 test windows each) and a clean 21/9 subject partition with
  **zero overlap**.
- *Influenced:* accuracy is a legitimate headline for this task (no resampling, no class weights), and
  the subject-disjoint split is what makes the reported accuracy honest.

**UCI HAR signal bands** — `fig_har_eda_03_acc_signal_bands.png`,
`fig_har_eda_04_gyro_signal_bands.png`

![HAR accelerometer bands](../ml/outputs/har/eda/fig_har_eda_03_acc_signal_bands.png)
![HAR gyroscope bands](../ml/outputs/har/eda/fig_har_eda_04_gyro_signal_bands.png)

- *Shows:* per-activity accelerometer and gyroscope signal envelopes.
- *Observed:* walking, walking-upstairs and walking-downstairs overlap heavily; sitting, standing and
  laying separate cleanly.
- *Influenced:* this is the chart that predicted the eventual confusion pattern. The app therefore
  groups the three walking variants as `isAmbulating` and never acts on the distinction — the weakest
  output is also the least used.

**UCI HAR gravity sanity and feature correlation** — `fig_har_eda_05_gravity_magnitude.png`,
`fig_har_eda_06_feature_correlation.png`

![HAR gravity magnitude](../ml/outputs/har/eda/fig_har_eda_05_gravity_magnitude.png)
![HAR feature correlation](../ml/outputs/har/eda/fig_har_eda_06_feature_correlation.png)

- *Shows:* the distribution of total acceleration magnitude, and the correlation among a fixed subset
  of the 561 features.
- *Observed:* the magnitude centres on **1.0323 g** (per-activity means 1.0075–1.0516 g) — a real
  accelerometer reading gravity — and the feature space is heavily redundant.
- *Influenced:* the gravity sanity check justifies converting live m/s² samples by dividing by 9.80665
  to match the dataset, and the redundancy supports the 24-feature on-device subset (B.7).

**Zone crossings and zone map** — `fig_04_zone_crossings_trend.png`, `fig_06_zone_map.png`

![Zone crossings trend](../ml/outputs/history/fig_04_zone_crossings_trend.png)
![Zone map](../ml/outputs/history/fig_06_zone_map.png)

- *Shows:* the monthly zone-crossing trend and the derived zone drawn over real trajectories.
- *Observed:* 430 entries / 426 exits over 58 months, from 431 of the 785 trajectories that intersect
  the derived zone.
- *Influenced:* this is the only real-world evidence in the project that a geofence boundary is crossed
  at a countable, non-degenerate rate — it grounds the geofence concept, though the app's own zone model
  is a configured rule, not this analysis.

**OpenStreetMap composition and map** — `fig_01_element_types.png`, `fig_06_element_map.png`

![OSM element types](../ml/outputs/osm/fig_01_element_types.png)
![OSM element map](../ml/outputs/osm/fig_06_element_map.png)

- *Shows:* element and tag composition of the Tiananmen 1.5 km × 1.5 km extract, and the elements drawn
  spatially.
- *Observed:* 15,980 elements at 5,681.4 nodes/km², dominated by buildings (1,205), highways (1,075) and
  untagged-as-`yes` buildings (1,160), with footways (504) and steps (104) prominent.
- *Influenced:* confirmed OSM is a usable key-less basemap for a pedestrianised tourist area, and
  informed the informational map-context feature. **No model consumes OSM data.**

**Weather profile** — `fig_02_monthly_precipitation.png`, `fig_04_harsh_weather_days.png`

![Monthly precipitation](../ml/outputs/weather/fig_02_monthly_precipitation.png)
![Harsh weather days](../ml/outputs/weather/fig_04_harsh_weather_days.png)

- *Shows:* monthly precipitation and the count of harsh days per month, for both sites.
- *Observed:* `huangshan_trek` receives 2,676.3 mm over 574 days (218 harsh days, 38.0 %) versus
  806.4 mm (175 harsh days, 30.5 %) at `geolife_centroid`, with the harsh days driven mostly by cold.
- *Influenced:* established an explicit, reproducible harsh-day rule and confirmed that the two sites
  have genuinely different risk profiles. Weather is presented as **context only** and is not an input
  to any model.

---

## C. Qualitative analysis

### C.1 Real correctly-classified examples (shipped reference windows)

These are the actual reference windows shipped in the app's test resources and asserted by the Kotlin
parity tests. *MEASURED* from `ml/outputs/har/train/har_reference_cases.json`,
`ml/outputs/har/fall/har_fall_reference_cases.json`, `ml/outputs/future_risk/future_risk_reference_cases.json`.

**Activity model — 12/12 correct on the reference set:**

| Subject | True | Predicted (argmax) | Confidence |
|---|---|---:|---:|
| 2 | STANDING | STANDING | 0.823 |
| 9 | WALKING_UPSTAIRS | WALKING_UPSTAIRS | 0.528 |
| 20 | SITTING | SITTING | 0.703 |
| 24 | LAYING | LAYING | 0.999 |
| 18 | WALKING_UPSTAIRS | WALKING_UPSTAIRS | 0.802 |
| 24 | STANDING | STANDING | 0.729 |
| 20 | SITTING | SITTING | 0.906 |
| 13 | WALKING_UPSTAIRS | WALKING_UPSTAIRS | 0.537 |
| 9 | SITTING | SITTING | 0.726 |
| 13 | STANDING | STANDING | 0.986 |
| 24 | STANDING | STANDING | 0.838 |
| 9 | WALKING | WALKING | 0.537 |

Note the **low confidences on the walking variants** (0.528, 0.537, 0.537). The model is right, but
barely — and that is the honest shape of the result: the three walking classes are genuinely
separable-looking to a phone and genuinely confusable to this model. Confidence is shown in the UI
for exactly this reason.

**Impact detector — 12/12 correct on the reference set:** six injected-impact windows scored
p(FALL) 1.0, 1.0, 1.0, 1.0, 1.0, 0.8917; six untouched negatives scored 0.0002, 0.0002, 0, 0.0568,
0, 0. This is a clean separation *on the injected family*, which is what the caveat in A.3.1 warns
about.

**Future-risk model — 10/10 imminent falls flagged HIGH on the reference set:**

| Subject | Activity | True | Lead (samples) | Raw | Calibrated | Band |
|---|---|---|---:|---:|---:|---|
| 2 | FallingForw | IMMINENT_FALL | 10 | 0.9848 | 0.9842 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 10 | 0.9663 | 0.9574 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 20 | 0.9104 | 0.8602 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 20 | 0.8241 | 0.7007 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 30 | 0.9307 | 0.8971 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 30 | 0.9533 | 0.9363 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 40 | 0.9102 | 0.8600 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 40 | 0.9508 | 0.9323 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 50 | 0.8545 | 0.7564 | HIGH |
| 2 | FallingForw | IMMINENT_FALL | 50 | 0.9106 | 0.8607 | HIGH |

Detection is present at the **full 1.0 s horizon**, not only at the last moment — which is the
interesting positive result (see also the lead-time table in D.4). Calibration pulls the raw
probabilities down slightly, as expected.

### C.2 Real incorrectly-classified examples — and they are in the shipped reference set

The future-risk reference file contains 6 negatives, and **one of them is a genuine false positive
that ships in the test resources**:

| Subject | Activity | True | Lead | Raw | Calibrated | Band | Verdict |
|---|---|---|---:|---:|---:|---|---|
| 5 | Walking | NO_IMMINENT_FALL | — | 0.5801 | **0.3373** | **HIGH** | **false positive** |
| 2 | Walking | NO_IMMINENT_FALL | — | 0.1006 | 0.0216 | LOW | correct |
| 3 | Walking | NO_IMMINENT_FALL | — | 0.0756 | 0.0147 | LOW | correct |
| 9 | Walking | NO_IMMINENT_FALL | — | 0.0700 | 0.0132 | LOW | correct |
| 9 | StandingUpFL | NO_IMMINENT_FALL | — | 0.0949 | 0.0199 | LOW | correct |
| 28 | Walking | NO_IMMINENT_FALL | — | 0.0904 | 0.0186 | LOW | correct |

This is not a defect in the test; it is an **honest boundary case deliberately preserved** so the
on-device walker is proven to reproduce scikit-learn's number *including* an uncomfortable one. It is
also a perfect illustration of the model's headline limitation: subject 5's ordinary walking was
scored 0.3373 — above the HIGH threshold — which is exactly how a 5.09-per-minute false-alarm rate
arises in practice.

### C.3 False positives and false negatives: counts, examples, implications

*MEASURED* from `ml/outputs/future_risk/test_predictions.csv` (10,415 held-out windows).

**Confusion matrix (shipped model, HIGH threshold 0.314046):**

|  | Predicted no | Predicted yes |
|---|---:|---:|
| **Actually no** | 8,583 | **493 (FP)** |
| **Actually yes** | **335 (FN)** | 1,004 |

**Real false negatives** — a real recorded fall approaching, missed (335 of 1,339 positives):

| Subject | Activity | Lead | Raw | Calibrated | Band |
|---|---|---:|---:|---:|---|
| 2 | FallingForw | 40 | 0.3421 | 0.1306 | MODERATE (not alarmed) |
| 2 | FallingForw | 30 | 0.0923 | 0.0192 | LOW |
| 2 | FallingForw | 20 | 0.2917 | 0.1009 | MODERATE (not alarmed) |
| 2 | FallingForw | 30 | 0.1992 | 0.0564 | LOW |

**Real false positives** — ordinary activity flagged:

| Subject | Activity | Raw | Calibrated | Band | Alarm |
|---|---|---:|---:|---|---|
| 2 | GoingUpS | 0.8287 | 0.7090 | HIGH | 1 |
| 2 | GoingUpS | 0.8474 | 0.7432 | HIGH | 1 |
| 2 | GoingDownS | 0.8550 | 0.7573 | HIGH | 1 |
| 2 | GoingDownS | 0.8994 | 0.8400 | HIGH | 1 |

**Where the false positives come from** (the mechanism, not the average):

| Activity | FPs | Activity | FPs |
|---|---:|---|---:|
| LyingDownFS | 121 | GoingDownS | 30 |
| Jumping | 115 | Running | 29 |
| SittingDown | 109 | Walking | 21 |
| StandingUpFL | 31 | StandingUpFS | 20 |
| | | GoingUpS | 17 |

The three worst confusions — **lying down, jumping, sitting down** — are precisely the three
activities where a body genuinely *drops* under gravity. Walking and running, which supply the most
windows, are the safest. This is a physically explicable error mode, not noise.

**Practical implications, stated plainly:**

- *A missed fall (FN) at 0.2–0.4 s lead is the dominant error* — 335 of 1,339 positives. Because a
  single fall spans many overlapping windows, the **trial-level** sensitivity is much better than the
  window-level recall: **264 of 284 held-out fall trials were flagged at least once (0.9296)**. Window
  recall (0.7498) flatters nothing here — it is reported because it is the lower, honest number.
- *A false positive is cheap by design and expensive in practice.* Nothing operational happens on a
  HIGH band: it is an informational UI state plus a throttled summary. But 5.09 false alarms per
  minute means a tourist who jumps, sits and lies down will see the card change state often, and the
  authority dashboard's elevated-risk count will be noisy. This is the reason the feature is
  **explicitly not a deployable alarm**.

### C.4 Example GPS tracks and scored anomalies (GeoLife)

**Real top anomalies** (*MEASURED*, `ml/outputs/eval/top_anomalies.csv`) — the highest-scoring points
from the held-out test split:

| user | trajectory | timestamp | lat | lon | speed_mps | step_m |
|---|---|---|---:|---|---:|---:|
| 106 | 20071008231624 | 2007-10-09 09:21:32 | 39.99173 | 116.34537 | **563.50** | 6,762.05 |
| 106 | 20071008231624 | 2007-10-09 09:23:21 | 39.97692 | 116.34237 | 213.23 | 1,492.64 |
| 105 | 20071005052724 | 2007-10-05 17:08:15 | 39.97710 | 116.29445 | 262.19 | 3,146.30 |
| 153 | 20081028101254 | 2008-10-28 22:35:39 | 39.96697 | 116.34521 | 268.19 | 536.37 |
| 062 | 20081215035525 | 2008-12-15 04:09:46 | 39.95385 | 116.45423 | 281.73 | 1,690.40 |
| 024 | 20090118040106 | 2009-01-18 07:37:41 | 40.03188 | 116.34208 | 156.78 | 783.88 |
| 030 | 20090615004838 | 2009-06-15 00:48:48 | 40.01149 | 116.31431 | 66.90 | 334.48 |

A speed of 563 m/s is not a person; it is a **GPS fix artefact** — a jumped position between two
timestamps. The detector's job is to rank these above normal movement, and it does. **The honest
interpretation:** the highest-scoring "anomalies" in real human trajectory data are mostly equipment
artefacts, not danger. This is the strongest single piece of evidence for why the score is labelled
"movement anomaly" and not "risk".

**Real single-track scoring:** `ml/outputs/predict/20081023025304_scored.csv` (115.7 KB) and
`ml/outputs/predict/track_scored.csv` (3.5 KB) are per-point scored tracks produced by `ml/predict.py`;
the SYNTHETIC fallback writes its own loudly-labelled
`ml/outputs/predict/SYNTHETIC/…SYNTHETIC_WARNING.txt`.

### C.5 Geofence entry/exit evidence

Real, from `ml/outputs/history/` (see B.6): over **58 months**, the derived Beijing zone saw
**430 entries and 426 exits** across **431** crossing trajectories out of 785 that intersect it, with
a monthly trend (`fig_04_zone_crossings_trend.png`) and a map (`fig_06_zone_map.png`).

This is **GeoLife-based** evidence that the geofence concept corresponds to something that really
happens at a countable rate — it is *not* evidence from the app's own zone model, which is a
configured polygon tested in `app/` and logged by `backend/`. The app-side geofence workflow is
verified by unit tests and by the backend acceptance run (which delivers a `ZONE_ALERT` event and
reads it back), and by the live client test described in C.7. It has **never** been exercised by a
real person walking across a real boundary.

### C.6 Normal, unusual, and model-predicted: three different things

| Observation | Source | What it is | What it is not |
|---|---|---|---|
| Speed 2.07 m/s median, 09:00 peak | GeoLife EDA | **Normal** human movement | Not a safety statement |
| Speed 563 m/s at a GPS fix | GeoLife top anomalies | **Unusual** — almost certainly an artefact | Not danger, not a person |
| FallingForw, calibrated 0.984, lead 10 samples | UniMiB held-out window | **Model-predicted** imminent impact | Not proof a fall occurred; a lab window |
| Walking, calibrated 0.337, HIGH band | UniMiB held-out window | **Model's false positive** | Not an emergency |

These four rows are the whole argument of this project in miniature. The app shows all of them, and
labels which is which.

### C.7 Usability, reliability, offline behaviour, authority workflow

Assessed **only from documented, executed tests** — never from user feedback, because none exists:

| Aspect | Evidence | Verdict |
|---|---|---|
| Offline inference | All three shipped models are JSON forests walked by hand-written Kotlin; no network, no runtime library | **Verified by test**, not by observation on a device |
| Offline queueing | The app's outbox stores events in SQLite and retries; a `RISK` summary is a separate `OutboxKind.RISK` | Verified by `FutureRiskReporterTest` (12) against real SQLite, and by the live test "a dead backend leaves the SOS queued and reports why" |
| Prediction → authority | A **throttled summary** (never a sensor stream) with a dedupe key on the prediction instant | Verified end-to-end by `backend/verify_integration.py` (41/41 PASS) which read the prediction back from `/authority/risk` **while `sosTotal` stayed unchanged** |
| A prediction is not an incident | `backend/tsm/api.py` deliberately ignores risk when computing `state`; `backend/tests/test_risk.py` (28 tests) asserts a HIGH prediction leaves `latestSos` empty and `openSosCount == 0` | Verified |
| Role separation | The production Kotlin client read `GET /authority/tourists` as an **officer**, then presented a **tourist** token and received **HTTP 403** | Verified over a real socket |
| Warnings / wording | The card must name the other two risk concepts and deny being them; it must state that the model never sends an SOS; factor text must be non-causal | Pinned by `FutureRiskCardModelTest` (18) and `FutureRiskAnalyzerTest` (18) |
| Rendering on a screen | No emulator and no device existed in this environment (`adb devices` empty) | **NOT CONDUCTED** |

### C.8 Interpretation of model behaviour, features, and limits

**What the models lean on** (*MEASURED*, saved feature importances):

- Transport model: `p95_speed_mps` 0.200, `distance_m` 0.186, `mean_speed_mps` 0.160,
  `straightness` 0.117, `duration_s` 0.083 — i.e. **speed and distance dominate**, which is a
  plausible and unsurprising transport signature.
- HAR activity model: on-device feature importances are plotted in
  `ml/outputs/har/train/fig_har_04_feature_importance.png`; the measured result that matters is that
  **adding the three gravity-direction features (mean per-axis acceleration) raised accuracy from
  0.7581 to 0.8351** — a documented ablation recorded in `docs/progress_log.md` and flagged in
  `docs/model_card_movement.md` as a *development measurement*, not a shipped artefact.
- Future-risk model: ships the forest's global Gini importance plus per-feature training mean/std; the
  app ranks features by `importance × |z-score|` and shows at most 3. **The card states, and a test
  asserts, that these are "readings this model leans on", not causes.**

**Interpretation limits, explicitly:**

- No causal claim is made anywhere. The factor list is not a diagnosis.
- The transport model is **not** a risk model, and the doc says so in its own title paragraph.
- The unsupervised score is not a probability, and its scale is arbitrary
  (`movement_anomaly_score = -score_samples(scaled_features)`; train mean 0.4141, test mean 0.4080).
- Activity confidence is not a safety score; a 0.53 "WALKING" is a guess, not a certainty.
- The future-risk probability **is** calibrated, and is shown *only because* it is calibrated — and
  even then the mid-range bins are known to be over-confident (D.4).

### C.9 NOT CONDUCTED

Stated plainly, because the absence is part of the record:

- **No user study, interview, survey, diary study or usability evaluation of any kind.**
- **No real tourist ever used this system.** No outdoor, weather, bag-carry or vehicle testing.
- **No on-device or instrumented test run** — no emulator, no phone (`adb devices` empty). The
  Compose screens, permission dialogs, real GPS, real sensor chain and the device HTTP stack were
  **never exercised**.
- **No real-world fall detection.** UCI HAR has no falls; UniMiB falls are laboratory falls.
- **No phone-drop test.** The detector cannot distinguish a dropped phone from a falling person, and
  no dataset available here contains a phone-drop class.
- **No clinical, medical or emergency-services validation.** This is not a medical device.

---

## D. Quantitative evaluation

### D.0 The metrics, in plain language, and why they matter for tourist safety

| Metric | Plain meaning | Why it matters here |
|---|---|---|
| TP / TN / FP / FN | Counts of the four outcomes | The counts tell you the *operational* cost, which percentages hide |
| Accuracy | Share of all decisions that were right | Misleading under imbalance; reported only where classes are balanced or alongside precision/recall |
| Precision | Of the things flagged, how many were real | Every false positive is a needless warning to a tourist or an officer |
| Recall / sensitivity | Of the real events, how many were caught | A missed fall is the worst outcome for a safety feature |
| Specificity | Of the non-events, how many were correctly left alone | The other half of the false-alarm problem |
| F1 | Precision and recall combined | Threshold selection here; not a safety guarantee |
| ROC-AUC | Chance a random positive outranks a random negative | Threshold-independent ranking ability |
| PR-AUC | Same idea, focused on the positive class | **The right headline under imbalance** — here the baseline is the 12.86 % positive rate, not 0.5 |
| Per-class + macro/weighted averages | Multiclass fairness | Stops a big class (walk) from hiding a broken small class (taxi, train) |
| Threshold analysis | How the trade-off moves | HIGH/MODERATE bands are exactly this, fitted on validation only |
| Calibration (Brier + reliability) | Whether a stated probability means anything | A "70 %" that is right 50 % of the time misleads |
| Event / subject-level metrics | Per-incident or per-person, not per-window | Overlapping windows inflate window metrics; trial-level sensitivity is the honest number |

### D.1 Trajectory anomaly detector (GeoLife, unsupervised)

**Model:** `sklearn.ensemble.IsolationForest`, 200 trees, contamination 0.02, seed 42, over 14
standardised movement features. Score convention: `-score_samples(scaled_features)`; higher = more
anomalous.

**Training/evaluation scope:** 1,060,493 train points / 369,247 test points, from **900 / 300**
trajectories, split by `traj_uid` — **0 group overlap** (*MEASURED*, `ml/models/model_meta.json`,
`ml/outputs/train/train_report.json`). Runtime 76.7 s (train), 74.01 s (evaluate).

**Supervised metrics: `NOT AVAILABLE`.** GeoLife has no anomaly or danger labels, so accuracy, F1,
precision, a confusion matrix and a *real* ROC-AUC **cannot be computed honestly**. This is recorded
in the artefact itself (`supervised_metrics_available: false`, with the reason).

**Injection-based validation** (*labels synthetic, data real* — `ml/outputs/eval/eval_report.json`):

| Quantity | Value |
|---|---|
| Protocol | GPS teleport bursts (0.02°, run length 3) injected into **real held-out trajectories** |
| Points marked affected | **17,462** |
| ROC-AUC | **0.8922** |
| Average precision (PR-AUC) | **0.6763** |
| Precision@k / Recall@k (k = 17,462) | **0.6165** / **0.6165** |
| **Label-shuffle control ROC-AUC** | **0.5007** (≈ chance — the metric is meaningful) |
| Held-out set | 369,247 points / 300 trajectories, 0 group overlap |

**Threshold / quantile analysis** (*MEASURED*, `ml/outputs/eval/eval_metrics.csv`):

| Score quantile | Threshold | Fraction flagged |
|---|---:|---:|
| 0.90 | 0.4588 | 10.00 % |
| 0.95 | 0.4853 | 5.00 % |
| **0.98** | **0.5299** | **2.00 %** |
| 0.99 | 0.5810 | 1.00 % |
| 0.995 | 0.6374 | 0.50 % |

Score distribution (*MEASURED*): train mean 0.4141, std 0.0479, p50 0.4000, p95 0.5056, p99 0.5939;
test mean 0.4080, std 0.0437, p50 0.3959, p95 0.4853, p99 0.5810. Train and test distributions match
closely, which is the check that the model is not simply memorising the training split.

**Interpretation:** ROC-AUC 0.8922 means the detector separates *injected teleports* from normal
movement well. It does **not** mean it detects danger. Only one anomaly family was tested; detours and
unusual stops are uncovered.

### D.2 Activity recognition (UCI HAR)

**Split:** the dataset's official, **subject-disjoint** split — 7,352 train / 2,947 test windows, 21 /
9 subjects, overlap 0.

| Model | Features | Trees | Accuracy | Macro-F1 |
|---|---:|---:|---:|---:|
| Report reference | 561 | 300 | **0.9287** | **0.9271** |
| **On-device (shipped)** | **24** | **32** | **0.8351** | **0.8333** |
| Single decision tree (baseline) | 24 | 1 | 0.7967 | 0.7943 |

*Both comparisons are reported* because either alone misleads: the shipped model is **0.0936 behind**
the 561-feature reference and **0.0384 ahead** of a single tree.

**Per-class results — shipped 24-feature model** (*MEASURED*):

| Activity | Precision | Recall | F1 | Support |
|---|---:|---:|---:|---:|
| WALKING | 0.7363 | 0.8669 | 0.7963 | 496 |
| WALKING_UPSTAIRS | 0.7380 | 0.7176 | 0.7277 | 471 |
| WALKING_DOWNSTAIRS | 0.9507 | 0.7810 | 0.8575 | 420 |
| SITTING | 0.7996 | 0.8045 | 0.8020 | 491 |
| STANDING | 0.8185 | 0.8139 | 0.8162 | 532 |
| LAYING | 1.0000 | 1.0000 | 1.0000 | 537 |
| **Macro average** | **0.8405** | **0.8307** | **0.8333** | **2,947** |

**Per-class results — 561-feature reference model** (*MEASURED*): WALKING p 0.8996 / r 0.9758 / F1
0.9362; WALKING_UPSTAIRS 0.9036 / 0.9151 / 0.9093; WALKING_DOWNSTAIRS 0.9677 / 0.8571 / 0.9091;
SITTING 0.9119 / 0.8859 / 0.8988; STANDING 0.8974 / 0.9211 / 0.9091; LAYING 1.0 / 1.0 / 1.0; macro
0.9301 / 0.9258 / 0.9271.

**Threshold analysis:** not applicable — the model reports an argmax over six classes. **Calibration:
`NOT AVAILABLE`** (no probabilities were calibrated; the raw forest vote fractions are shown as
confidence). **ROC-AUC / PR-AUC: `NOT AVAILABLE`** for the saved report (no one-vs-rest curves were
written). **Per-class specificity: `NOT AVAILABLE`** — the confusion matrices exist as figures
(`fig_har_02_confusion_561.png`, `fig_har_03_confusion_deployable.png`) but were not saved as
numbers.

**Event/subject-level metrics:** the dataset's official split is a single fold; no repeated
subject-disjoint evaluation exists. LAYING's 1.0 is a genuine property of the data but is **one
held-out fold of thirty subjects** and is reported as such.

### D.3 Impact / fall-signature detector (UCI HAR + injected impacts)

| Metric | Value |
|---|---:|
| Test windows | 1,848 (924 positive / 924 negative) |
| ROC-AUC | **0.9999** |
| Average precision (PR-AUC) | **0.9999** |
| F1 @ 0.5 | **0.9962** |
| Precision @ 0.5 | 0.9957 |
| Recall @ 0.5 | 0.9968 |
| **Shuffled-label control ROC-AUC** | **0.5198** |

**Confusion matrix — DERIVED from the saved precision/recall and n = 924 per class** (the report
stores metrics, not counts): precision 0.9957 → TP = 921, FP = 4; recall 0.9968 → FN = 3; TN = 920.

|  | Predicted NO_FALL | Predicted FALL |
|---|---:|---:|
| **Actually NO_FALL** | 920 | 4 |
| **Actually FALL** | 3 | 921 |

*DERIVED accuracy* = 1,841 / 1,848 = **0.9962**; *DERIVED specificity* = 920 / 924 = **0.9957**.

**Why the shuffled-label control matters:** the same pipeline with permuted labels collapses to
ROC-AUC 0.5198 — indistinguishable from chance. So the near-perfect score is the detector genuinely
finding the injected signature in real held-out subjects' recordings, not leakage, duplicated windows
or a feature that secretly encodes the label.

**Why it is still not a fall detector:** an injected impact is a large, stereotyped, high-signal
transient, and the synthetic positives share a generator. A real fall is messier, sometimes gentle,
and often looks like sitting down quickly. The saved caveat says exactly this.

**Training set size: `NOT AVAILABLE`** — the report records the test windows (1,848) but not the
training window count.

### D.4 Future-risk predictor (UniMiB SHAR, shipped)

**Split:** subject-disjoint, 60/20/20 — **18 / 6 / 6** subjects; 31,122 / 9,591 / 10,415 windows.
Validation subjects [12, 13, 15, 18, 24, 29]; test subjects [2, 3, 5, 9, 14, 28]. The test set was
**touched once**, after every choice was frozen.

**Baselines and the shipped model** (*MEASURED*, `ml/outputs/future_risk/metrics.json`):

| Model | Precision | Recall | F1 | ROC-AUC | PR-AUC |
|---|---:|---:|---:|---:|---:|
| Majority class | 0.0000 | 0.0000 | 0.0000 | 0.5000 | 0.128565 |
| Logistic regression | 0.4760 | 0.8432 | 0.6085 | 0.904389 | 0.659633 |
| **Hist gradient boosting** (best, **not shipped**) | **0.7916** | 0.6721 | **0.7270** | **0.945913** | **0.815000** |
| Random forest, raw vote @ 0.5 | 0.6534 | 0.7827 | 0.7122 | 0.936848 | 0.764569 |
| **Random forest, calibrated, HIGH ≥ 0.314046 (shipped)** | **0.6707** | **0.7498** | **0.7080** | **0.936848** | **0.764569** |

**Confusion matrix, shipped model at the HIGH threshold:**

|  | Predicted no | Predicted yes |
|---|---:|---:|
| **Actually no** | 8,583 | 493 |
| **Actually yes** | 335 | 1,004 |

*MEASURED* accuracy **0.9205**; Brier 0.056675; threshold 0.314046.
*DERIVED specificity* = 8,583 / 9,076 = **0.9457**.

**Calibration** (Platt scaling fitted on validation only; a = 1.24940294, b = −1.07904853):

| Metric (held-out test) | Raw forest | Calibrated |
|---|---:|---:|
| Brier score | 0.062600 | **0.056675** |

Reliability, 10 bins (*MEASURED*): bin 0.0–0.1 → n 8,376, mean predicted 0.0122, observed 0.0224;
0.1–0.2 → 326 / 0.1450 / 0.1902; 0.2–0.3 → 193 / 0.2482 / **0.3886**; 0.3–0.4 → 140 / 0.3461 /
**0.5000**; 0.4–0.5 → 123 / 0.4482 / 0.4553; 0.5–0.6 → 135 / 0.5517 / 0.4296; 0.6–0.7 → 138 / 0.6520 /
0.5217; 0.7–0.8 → 189 / 0.7504 / 0.5608; 0.8–0.9 → 299 / 0.8589 / 0.6957; 0.9–1.0 → 496 / 0.9407 /
0.8952.

**Read the reliability table honestly:** calibration improves the Brier score and the top bin is
close, but the **middle bins (0.2–0.4) are badly over-confident** — 140 windows predicted at ~0.35
were positive 50 % of the time. The probability is shown in the app *only because it is calibrated*,
and always alongside the band, the thresholds and the model version.

**Threshold analysis (risk bands, fitted on validation then frozen):**

| Band | Rule | Threshold |
|---|---|---:|
| **HIGH** | threshold maximising F1 on validation | **≥ 0.314046** |
| **MODERATE** | **largest** threshold whose validation recall is still ≥ 0.95, capped at HIGH | **≥ 0.069201** |
| **LOW** | below MODERATE | — |

`UNAVAILABLE` is **not** a band — it is a separate state ("the model could not predict"), never mapped
to LOW, and the backend **rejects** it if a client uploads it.

**Detection over the horizon** (*MEASURED*) — detection does **not** collapse as the lead grows, which
is the interesting positive result:

| Lead | Positive windows | Detected | Detection rate |
|---|---:|---:|---:|
| 0.2 s | 284 | 204 | 0.7183 |
| 0.4 s | 281 | 199 | 0.7082 |
| 0.6 s | 281 | 217 | 0.7722 |
| 0.8 s | 270 | 213 | 0.7889 |
| 1.0 s | 223 | 171 | 0.7668 |

**Trial-level sensitivity — the number that matters most:**

| Metric | Value |
|---|---:|
| Held-out fall trials | **284** |
| Trials flagged HIGH at least once | **264** |
| **Trial sensitivity** | **0.9296** |

**False alarms on ordinary activity — the headline limitation:**

| Metric | Value |
|---|---:|
| ADL windows on test subjects | 9,076 |
| ADL windows flagged HIGH | 493 |
| ADL window false-positive rate | 0.0543 |
| **False alarms per minute at the app's 0.64 s cadence** | **5.09** |
| False alarms per minute at the raw 0.2 s window stride | 16.30 |
| ADL seconds evaluated | 1,815.2 |

Per-activity false-alarm rates (so the limitation is visible, not averaged away): Walking 0.0111
(21/1,896) · Running 0.0164 (29/1,767) · Going upstairs 0.0253 (17/671) · Going downstairs 0.0442
(30/678) · **Jumping 0.1606 (115/716)** · **Sitting down 0.1564 (109/697)** · Standing up from sofa
0.0276 (20/725) · **Lying down from sofa 0.1274 (121/950)** · Standing up from floor 0.0318 (31/976).

**Post-impact diagnostic** (not training data, not a test metric — labelled as a diagnostic in
`metrics.json`): 970 windows, 72 alarms, rate 0.0742.

**Leakage controls, checked rather than asserted** (*MEASURED*, `metrics.json`): all positive windows
end before their own impact (**true**); all leads within the horizon (**true**); feature window never
overlaps the label window (**true**); minimum/maximum lead 10 / 50 samples; max end index over all
positives 266. Second control: the **shuffled-label run** scores ROC-AUC **0.47743** / PR-AUC
**0.129885** — chance. If the construction leaked, this would exceed 0.5.

### D.5 Transport-mode classifier (GeoLife labels)

*Explicitly **not** a risk model.*

| Metric | Value |
|---|---:|
| Labelled segments | 9,584 from 69 users (14,718 labels seen) |
| Skipped | 5,130 intervals with < 2 points; 4 with < 1.0 m movement |
| Split | **subject-disjoint** — 45 train / 19 test users; 8,036 / 1,548 segments; overlap False |
| Classes present | 11 (airplane, bike, boat, bus, car, motorcycle, run, subway, taxi, train, walk) |
| **Accuracy** | **0.8475** |
| **Macro-F1** | **0.5784** |
| **Weighted-F1** | **0.8507** |
| *DERIVED macro precision / recall* | 0.5922 / 0.5775 (10 classes with test support; `airplane` has support 0) |

**Per-class** (*MEASURED*): bike p 0.8363 / r 0.8214 / F1 0.8288 (n 168) · bus 0.7980 / 0.8390 / 0.8180
(292) · car 0.6780 / 0.5195 / 0.5882 (77) · motorcycle 1.0 / 1.0 / 1.0 (1) · subway 0.8313 / 0.5897 /
0.6900 (117) · taxi 0.2813 / 0.5094 / 0.3624 (53) · train 0.5455 / 0.5455 / 0.5455 (11) · walk
0.9516 / 0.9504 / 0.9510 (827) · airplane 0 / 0 / 0 (support 0) · boat 0 / 0 / 0 (1) · run 0 / 0 / 0 (1).

*Note on macro-F1:* 0.5784 is the mean over the **10** classes that have test support; `airplane`
(support 0) is excluded, which is why the simple average of the printed per-class F1 values over 11
classes (0.5258) does not equal it. Both numbers are derivable from the saved artefact.

**Real confusion matrix** (*MEASURED*, `ml/outputs/transport/table_03_confusion.csv`) — rows true,
columns predicted, 11 classes (abridged to the classes that matter):

| true ↓ / pred → | bike | bus | car | subway | taxi | train | walk |
|---|---:|---:|---:|---:|---:|---:|---:|
| bike | **138** | 6 | 1 | 0 | 1 | 0 | 22 |
| bus | 6 | **245** | 9 | 5 | 21 | 0 | 6 |
| car | 0 | 15 | **40** | 1 | 16 | 4 | 1 |
| subway | 0 | 8 | 4 | **69** | 28 | 1 | 7 |
| taxi | 1 | 19 | 4 | 0 | **27** | 0 | 2 |
| train | 0 | 1 | 0 | 4 | 0 | **6** | 0 |
| walk | 20 | 13 | 1 | 4 | 3 | 0 | **786** |

The structure is readable: `walk` is nearly perfect; `taxi` is the disaster (27/53, mostly called
`bus`); `car` and `subway` leak into `bus` and `taxi`. **ROC-AUC and PR-AUC: `NOT AVAILABLE`** (no
curves were saved for this model). **Calibration: `NOT AVAILABLE`.**

### D.6 Metrics that are derived, collected in one place

| Derived metric | Formula | Value | Source of the inputs |
|---|---|---:|---|
| Impact detector accuracy | (920+921)/1,848 | 0.9962 | `har_fall_report.json` |
| Impact detector confusion | from precision 0.9957 & recall 0.9968, n=924 | TP 921, FP 4, FN 3, TN 920 | same |
| Impact detector specificity | 920/924 | 0.9957 | same |
| Future-risk specificity | 8,583/(8,583+493) | 0.9457 | `metrics.json` confusion |
| Future-risk majority-class accuracy | 1 − 0.128565 | 0.8714 | same |
| Future-risk test positive rate | 1,339/10,415 | 0.12857 | same |
| Transport macro precision (10 classes) | mean of per-class precision, airplane excluded | 0.5922 | `model_metrics.json` |
| Transport macro recall (10 classes) | mean of per-class recall, airplane excluded | 0.5775 | same |

---

### D.7 Evaluation figure gallery

Real output files from the evaluation runs, each with what it shows and what to read from it. These
are the *actual* figures the numbers in D.1–D.5 come from.

**Future-risk — precision–recall and ROC** (`ml/outputs/future_risk/figures/`)

![Future-risk precision-recall](../ml/outputs/future_risk/figures/fig_01_precision_recall.png)
![Future-risk ROC](../ml/outputs/future_risk/figures/fig_02_roc.png)

- *Shows:* PR-AUC 0.7646 and ROC-AUC 0.9368 for the shipped forest, drawn against the baselines.
- *Read this from it:* the PR curve is the meaningful one, because the baseline is the 12.86 % positive
  rate, not 0.5. The ROC curve looks flattering by comparison — which is exactly why both are shown.

**Future-risk — calibration** (`fig_03_calibration.png`)

![Future-risk calibration](../ml/outputs/future_risk/figures/fig_03_calibration.png)

- *Shows:* reliability against the diagonal, raw versus calibrated.
- *Read this from it:* the top bin tracks the diagonal and the Brier score improves (0.062600 →
  0.056675), but the **0.2–0.4 bands sit well above the diagonal** — 140 windows predicted at ~0.35
  were positive 50 % of the time. This chart is the reason the probability is only shown alongside the
  band and the thresholds.

**Future-risk — confusion matrix and lead time** (`fig_04_confusion_matrix.png`, `fig_05_lead_time.png`)

![Future-risk confusion matrix](../ml/outputs/future_risk/figures/fig_04_confusion_matrix.png)
![Future-risk lead time](../ml/outputs/future_risk/figures/fig_05_lead_time.png)

- *Shows:* the 2×2 counts at the HIGH threshold (TN 8,583 / FP 493 / FN 335 / TP 1,004), and detection
  rate as the lead grows from 0.2 s to 1.0 s.
- *Read this from it:* the lead-time panel is the interesting positive result — detection does **not**
  collapse at the full 1.0 s horizon (0.72 → 0.77), so there is real pre-impact signal rather than just
  "the last moment before the peak".

**Future-risk — example windows** (`fig_06_example_windows.png`)

![Future-risk example windows](../ml/outputs/future_risk/figures/fig_06_example_windows.png)

- *Shows:* real accelerometer windows from held-out subjects, positive and negative.
- *Read this from it:* the signature the model keys on, and how ordinary sitting and lying down can
  resemble a drop — the mechanism behind the three worst false-positive activities (C.3).

**Impact detector — ROC, confusion, injected signal** (`ml/outputs/har/fall/`)

![Impact detector ROC](../ml/outputs/har/fall/fig_har_fall_01_roc.png)
![Impact detector confusion](../ml/outputs/har/fall/fig_har_fall_02_confusion.png)
![Injected impact signal](../ml/outputs/har/fall/fig_har_fall_03_signal.png)

- *Shows:* ROC-AUC 0.9999, the confusion matrix, and one injected impact waveform.
- *Read this from it:* the signal panel is the caveat made visible — the positives are a large,
  stereotyped, high-signal transient produced by one generator. That is why 0.9999 cannot be read as
  fall-detection performance.

**Activity model — per-class confusion, accuracy vs features, importance** (`ml/outputs/har/train/`)

![Activity confusion (561 features)](../ml/outputs/har/train/fig_har_02_confusion_561.png)
![Activity confusion (on-device, 24 features)](../ml/outputs/har/train/fig_har_03_confusion_deployable.png)

- *Shows:* the two confusion matrices side by side.
- *Read this from it:* the on-device matrix loses accuracy almost entirely in the **walking triads**;
  sitting, standing and laying stay strong. That is the trade the app accepts, and the reason
  `isAmbulating` exists.

![Accuracy vs features](../ml/outputs/har/train/fig_har_05_accuracy_vs_features.png)
![Activity feature importance](../ml/outputs/har/train/fig_har_04_feature_importance.png)

- *Shows:* accuracy against the number of features (1 → 561) and forest feature importances.
- *Read this from it:* the curve flattens past ~50 features — 561 features buy +0.09 accuracy for 23×
  the computation, which is what made the 24-feature on-device set defensible.

**Transport model — confusion matrix and feature importance** (`ml/outputs/transport/`)

![Transport confusion matrix](../ml/outputs/transport/fig_02_confusion_matrix.png)
![Transport feature importance](../ml/outputs/transport/fig_03_feature_importance.png)

- *Shows:* the 11×11 confusion matrix on unseen users, and the nine feature importances.
- *Read this from it:* `walk` is nearly perfect and `taxi` collapses into `bus`; speed- and
  distance-family features dominate importances. It is a transport classifier, **not a risk model**.

---

## E. Model-training methodology

### E.1 Trajectory anomaly detector — `ml/train.py`

| Item | Value |
|---|---|
| Objective | Rank GPS points by how unusual their movement is |
| Target definition | **None** — unsupervised; no labels exist |
| Input features (14, fixed order) | `speed_mps`, `log_dt_s`, `step_m`, `accel_mps2`, `turn_deg`, `speed_mean_w5`, `speed_std_w5`, `step_mean_w5`, `turn_mean_w5`, `straightness_w5`, `altitude`, `alt_change`, `tod_sin`, `tod_cos` |
| Output | `movement_anomaly_score` = −score_samples(scaled features); higher = more anomalous; **not a probability** |
| Prediction horizon | None — a per-point score, not a forecast |
| Sampling / windowing | Point-level; `_w5` features are 5-point trailing windows; no resampling (dt is a feature) |
| Preprocessing | Standardisation of features (fitted on train); GPS-jump flagging; segment split at gaps > 120 s |
| Splitting | Grouped by `traj_uid`; 900 train / 300 test trajectories; 0 overlap |
| Leakage prevention | Trajectory-level grouping (never random point mixing) — directly mitigates the 847 colliding ids |
| Candidates trained | 1 (`IsolationForest`) |
| Architecture / hyper-parameters | 200 estimators, contamination 0.02, seed 42 |
| Environment | Python 3.11.9, scikit-learn 1.8.0, numpy 2.4.4 |
| Balancing / thresholds | Thresholds are **quantiles of the score**, not tuned: 0.98 → 0.5299 → 2.00 % flagged |
| Runtime | 76.7 s (train), 74.01 s (evaluate), 539.2 s (preprocess), 56.5 s (EDA) |
| Model-selection criteria | n/a — single unsupervised configuration |
| Saved format / version / location | `ml/models/isolation_forest.joblib` (1,753.5 KB), `model_meta.json`, `split.json` |
| Inference pipeline | `ml/predict.py` (analysis only; **this model does not run in the app**) |

### E.2 Activity recognition — `ml/har_train.py`

| Item | Value |
|---|---|
| Objective | 6-class activity classification |
| Target | UCI HAR's real human annotations (6 classes) |
| Input features | **561** for the reference model; **24** time-domain features on-device |
| Output | Class probabilities + argmax; the app shows the class **with its confidence** |
| Predicted horizon | None (current window) |
| Window / stride | 128 samples (2.56 s) @ 50 Hz; the dataset's own non-overlapping windows |
| Preprocessing | Dataset-provided filtering/gravity separation; units converted m/s² → g (÷ 9.80665) |
| Split | The dataset's **official subject-disjoint** split (21 train / 9 test) |
| Leakage prevention | Subject-disjoint by construction; verified overlap 0 |
| Candidates trained | 3 (561-feature RF 300 trees; 24-feature RF 32 trees depth 8; 24-feature single tree depth 8) |
| Seeds | fixed (`random_state`) |
| Class balancing | None needed (near-balanced); no weights applied |
| Threshold / calibration | argmax only; **no calibration** |
| Training duration | not separately recorded — `har_preprocess.py` + `har_train.py` are part of the 179-test ML run |
| Selection criteria | **deployment size**: the on-device model must be walkable in Kotlin from a JSON asset |
| Saved format / version / location | `app/src/main/assets/har_activity_model.json` (268,734 bytes) + `har_activity_model_spec.json` (3,479 bytes) |
| Training/deployment parity | `ml/har_verify_export.py`: max feature error 5.458e-07, max probability error 4.970e-07, **0 argmax mismatches**; a test asserts the app asset is **byte-identical** to the verified training output |
| Commands | `python ml\har_preprocess.py` → `python ml\har_train.py` → `python ml\har_verify_export.py` |

### E.3 Impact / fall-signature detector — `ml/har_fall.py`

| Item | Value |
|---|---|
| Objective | Binary detection of an impact signature |
| Target | **Synthesised impacts injected** into real recorded windows (positives); untouched windows (negatives) |
| Features | the same 24 time-domain features |
| Window | 128 samples @ 50 Hz |
| Split | subject-disjoint; held-out subjects [2, 4, 9, 10, 12, 13, 18, 20, 24]; trained on the other 21 |
| Leakage prevention | subject-disjoint + a shuffled-label control (ROC-AUC 0.5198) |
| Candidates | 1 (`RandomForestClassifier`, 40 trees) |
| Balancing | balanced positives/negatives by construction (924/924) |
| Threshold | 0.5 reported; the **app** gates on ≥ 0.55 **plus** ≥ 2.0 g impact **plus** post-impact stillness ≤ 0.12 g, with a 15 s cooldown |
| Saved location | `app/src/main/assets/har_fall_model.json` (59,679 bytes) |
| Parity | max feature error 6.342e-07, max probability error 4.736e-07, **0 argmax mismatches** |
| Recorded limitation | training window count `NOT AVAILABLE` (only the 1,848 test windows were saved) |

### E.4 Future-risk predictor — `ml/future_risk/train.py`, `tune.py`

| Item | Value |
|---|---|
| Objective | Will a hard fall impact occur within the next second? |
| Target | `IMMINENT_FALL` / `NO_IMMINENT_FALL`, from real UniMiB fall recordings |
| Horizon | **50 samples = 1.0 s** (the longest fully supported) |
| Feature window / stride | 50 samples (1.0 s) / 10 samples (0.2 s) |
| Impact threshold | peak magnitude ≥ **2.0 g** (shared with `FallPolicy.MIN_IMPACT_G`) |
| Positive labelling | windows ending in `[t_impact − 50, t_impact − 1]` (lead 1…50) |
| Negative labelling | same-cadence windows from ADL trials only; **post-impact windows are excluded** from training and kept as a diagnostic |
| Features (22, fixed order) | `acc_mag_mean/std/min/max/range/energy/peak_jerk/zcr`, `acc_x/y/z_mean`, `acc_x/y/z_std`, `acc_xy/xz/yz_corr`, `freefall_fraction`, `tilt_deg`, `orientation_change_deg`, `acc_mag_slope`, `acc_mag_late_to_early_ratio` |
| Preprocessing | **none that needs fitting** — every feature is scale-free per window, so there is no scaler that could leak a global mean into the test set |
| Split | subject-disjoint 60/20/20 (18/6/6); validation [12,13,15,18,24,29], test [2,3,5,9,14,28] |
| Candidates trained | **65 configurations** searched on validation only (`tune.py`, ~10 min) + logistic regression and hist gradient boosting baselines |
| Shipped architecture | `RandomForestClassifier`, 120 trees, max_depth 11, min_samples_leaf 4, `max_features="sqrt"`, `class_weight="balanced_subsample"` |
| Balancing | `balanced_subsample` (12.63 % positive) |
| Threshold selection | HIGH = max-F1 on validation (0.314046); MODERATE = largest validation-recall-≥-0.95 threshold (0.069201) |
| Calibration | Platt scaling on validation only; a = 1.24940294, b = −1.07904853; logit clipped at 1e-6 |
| Saved format / version / location | `app/src/main/assets/future_risk_model.json` = `ml/outputs/future_risk/future_risk_model.json`, **1,974,297 bytes**, model version **`future-risk-unimib-v1`** |
| Size budget | the forest must be ≤ 2,000,000 bytes to ship as an app asset — **binding** (only 1 of 65 configurations fitted) |
| Training duration | ~4 minutes for `train.py`; ~10 minutes for the 65-config search |
| Parity | `FutureRiskReferenceTest` (6) replays the shipped asset against scikit-learn's own probabilities on 16 held-out windows; feature parity, forest parity, calibration parity, and refusal of a wrong feature schema |
| Commands | `python ml/future_risk/train.py`; `python ml/future_risk/tune.py`; `powershell -File ml\run_all.ps1 -FutureRisk` |

### E.5 Transport-mode classifier — `ml/transport_mode.py`

| Item | Value |
|---|---|
| Objective | Next-segment transport-mode classification (**not risk**) |
| Target | GeoLife `labels.txt` transportation mode |
| Features (9) | `duration_s`, `distance_m`, `straightness`, `mean_speed_mps`, `p95_speed_mps`, `mean_turn_deg`, `log_n_points`, `tod_sin`, `tod_cos` |
| Segmentation | consecutive labelled intervals; segments < 2 points or < 1.0 m skipped |
| Split | subject-disjoint 45/19 users (8,036 / 1,548 segments), seed 42 |
| Model | `RandomForestClassifier`, 300 trees, `min_samples_leaf=2`, `class_weight="balanced_subsample"` |
| Balancing | balanced class weights (walk 6,460 vs motorcycle 2) |
| Runtime | 282.2 s |
| Saved location | `ml/outputs/transport/` (metrics, confusion, per-class, split, figures) |
| Deployed? | **No** — analysis only. It is not in the app and not in the backend |

### E.6 Environment, seeds, runtimes (reproducibility)

| Item | Value |
|---|---|
| Python | **3.11.9** (Windows) — pinned in `ml/requirements.txt` |
| Libraries | numpy 2.4.4, pandas 3.0.6, scipy 1.17.1, **scikit-learn 1.8.0**, matplotlib 3.11.2, pytest 9.1.1, joblib 1.5.3 |
| Seed | **42** everywhere |
| Hardware | a single Windows desktop, **~8 GB RAM**, no GPU, no device/emulator. The preprocessor is batched specifically to fit ~7.9 GB RAM |
| Recorded runtimes | preprocess 539.2 s · EDA 56.5 s · anomaly train 76.7 s · anomaly evaluate 74.01 s · HAR EDA 12.9 s · history 34.1 s · OSM 10.8 s (fetch 28.8 s) · weather 10.6 s · transport 282.2 s · future-risk train ~4 min · future-risk tune ~10 min |
| One-command reproduction | `powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1` with `-Network`, `-Transport`, `-FutureRisk` for the opt-in stages |

---

## F. Four different risk concepts, kept visibly separate

The project deliberately contains four things that a careless reader would merge. They are merged
**nowhere** in the code, the UI or the schema.

### F.1 Activity recognition (a *classification*, not a risk)

- **Input:** 24 time-domain features from a 2.56 s accelerometer/gyroscope window.
- **Output:** one of six activity classes + a confidence, shown in the UI.
- **Purpose:** describe what the person is doing ("is this person moving?").
- **Limitations:** laboratory data; ~1 window in 6 misclassified; the three walking variants are the
  weakest and most-confused outputs.
- **Must not be used as:** a safety score. `ActivityKind.isAmbulating` groups the three walking
  variants precisely because the distinction is unreliable, and the app never acts on activity alone.

### F.2 Movement / fall / future-impact prediction (a *movement* question, not a *danger* question)

Two separate models live here, and they answer different questions:

- **Impact detector (current window):** "does this window contain an impact signature?" Combined with
  the ≥ 2.0 g impact and stillness gates to produce a *suspected* fall. Positives are **synthetic**.
- **Future-risk predictor (next 1.0 s):** "will a hard impact land within the next second?" Trained on
  **real** laboratory falls from UniMiB SHAR, calibrated, and shown as LOW/MODERATE/HIGH.

Limitations, all documented: a 1-second horizon only; **5.09 false alarms per minute** of ordinary
activity at the app cadence; mid-range probabilities over-confident; the label is a fall **impact**,
not a tourist incident; and the model **cannot distinguish a dropped phone from a falling person**.
- **Must not be used as:** a deployable alarm, proof that an emergency occurred, or a trigger for SOS.
  **Risk never triggers SOS** — asserted by `backend/tests/test_risk.py`.

### F.3 Geographic risk (a *configured rule*)

- **Input:** the tourist's coordinates and the polygons the authority drew.
- **Output:** inside / outside a restricted zone; a `ZONE_ALERT` event when a boundary is crossed.
- **Purpose:** deterministic geographic compliance.
- **Limitations:** a rule, not a model. It has **no probability**, was never trained, and knows
  nothing about intent, danger or the person's condition. Its real-world plausibility rests on the
  GeoLife crossing evidence in B.6/C.5.

### F.4 Rule-based risk scoring (`ApproachRiskModel`)

- **Input:** published weighted signals — zone severity, the tourist's own recorded crossing history,
  distance, time of day, and live movement state.
- **Output:** an approach-risk score displayed in the app.
- **Purpose:** a documented, auditable heuristic.
- **Limitations:** **not a learned model.** It has **no probability**, no training data and no
  evaluation metrics, and it is described as a weighted checklist in every document that mentions it.
  It is *deliberately* not dressed up as a learned danger probability.

### F.5 The rules that enforce the separation

- Distinct types throughout (`FutureRiskBand`/`FutureRiskFactor`/`FutureRiskState` vs
  `RiskBand`/`RiskFactor`), so a screen cannot render the heuristic under the trained model's heading.
- The model card's own text names the other two concepts and denies being them; **a test asserts that
  sentence exists**.
- The card's footer states the model never sends an SOS by itself; **a test asserts that too**.
- `backend/tsm/api.py`'s `state` computation **ignores risk entirely**; a HIGH prediction leaves
  `latestSos` empty, `openSosCount == 0` and `state != "SOS"`.
- The dashboard shows predictions in their **own panel and tiles**, never folded into the SOS queue or
  the incident feed.
- The wire contract labels a prediction `PREDICTION`; the payload validator **rejects** an unreadable
  band, a probability outside [0,1], an absurd horizon, a missing version, a malformed data-quality
  block, and more than 5 factors.

---

## G. Results and scientific validity

### G.1 Consolidated results table

`N/A` = not applicable to that model's task. `NOT AVAILABLE` = computable in principle but never
saved, or impossible because labels do not exist.

| Model / component | Dataset | Evaluation split | Sample count | Accuracy | Precision | Recall | F1 | ROC-AUC | PR-AUC | Evidence source |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---|
| Trajectory anomaly (unsupervised) | GeoLife 1.3 | trajectory-disjoint held-out | 369,247 pts / 300 traj (17,462 injected) | NOT AVAILABLE | 0.6165 (P@k) | 0.6165 (R@k) | N/A | 0.8922 (injection) | 0.6763 | `ml/outputs/eval/eval_report.json` |
| Activity — 561-feature reference | UCI HAR | official subject-disjoint test | 2,947 windows / 9 subjects | 0.9287 | 0.9301 (macro) | 0.9258 (macro) | 0.9271 (macro) | NOT AVAILABLE | NOT AVAILABLE | `ml/outputs/har/train/har_train_report.json` |
| **Activity — 24-feature on-device (shipped)** | UCI HAR | same | 2,947 windows | **0.8351** | 0.8405 (macro) | 0.8307 (macro) | **0.8333** (macro) | NOT AVAILABLE | NOT AVAILABLE | same |
| Activity — single tree baseline | UCI HAR | same | 2,947 windows | 0.7967 | NOT AVAILABLE | NOT AVAILABLE | 0.7943 (macro) | NOT AVAILABLE | NOT AVAILABLE | same |
| **Impact detector (shipped)** | UCI HAR + **injected** impacts | subject-disjoint | 1,848 windows (924/924) | 0.9962 (DERIVED) | 0.9957 | 0.9968 | **0.9962** | **0.9999** | 0.9999 | `ml/outputs/har/fall/har_fall_report.json` |
| Future-risk — majority class | UniMiB SHAR | subject-disjoint test | 10,415 windows / 6 subjects | 0.8714 (DERIVED) | 0.0000 | 0.0000 | 0.0000 | 0.5000 | 0.128565 | `ml/outputs/future_risk/metrics.json` |
| Future-risk — logistic regression | UniMiB SHAR | same | 10,415 | NOT AVAILABLE | 0.4760 | 0.8432 | 0.6085 | 0.904389 | 0.659633 | same |
| Future-risk — hist gradient boosting (**not shipped**) | UniMiB SHAR | same | 10,415 | NOT AVAILABLE | 0.7916 | 0.6721 | 0.7270 | 0.945913 | **0.815000** | same |
| Future-risk — RF raw vote @ 0.5 | UniMiB SHAR | same | 10,415 | NOT AVAILABLE | 0.6534 | 0.7827 | 0.7122 | 0.936848 | 0.764569 | same |
| **Future-risk — RF calibrated @ HIGH ≥ 0.314046 (shipped)** | UniMiB SHAR | same | 10,415 (1,339 pos) | **0.9205** | 0.6707 | **0.7498** | 0.7080 | **0.936848** | 0.764569 | same |
| Transport-mode classifier | GeoLife `labels.txt` | subject-disjoint (45/19 users) | 1,548 segments | 0.8475 | 0.5922 (DERIVED, macro) | 0.5775 (DERIVED, macro) | 0.5784 (macro) / 0.8507 (weighted) | NOT AVAILABLE | NOT AVAILABLE | `ml/outputs/transport/model_metrics.json` |
| Geographic risk (geofence) | GeoLife crossing evidence | n/a | 430 entries / 426 exits | N/A | N/A | N/A | N/A | N/A | N/A | `ml/outputs/history/findings.md` (rule, not a model) |
| Approach-risk score | none | n/a | n/a | N/A | N/A | N/A | N/A | N/A | N/A | heuristic, not a model |

**Event / subject-level metrics, kept separate from window metrics:**

| Model | Subject/event-level metric | Value |
|---|---|---:|
| Future-risk | Trial sensitivity (≥1 HIGH window per held-out fall trial) | **0.9296** (264 / 284) |
| Future-risk | Window recall (for contrast) | 0.7498 |
| Trajectory anomaly | Group overlap between train and test trajectories | **0** |

### G.2 Model comparison

Only models that were **actually trained and evaluated** appear here.

**On UniMiB SHAR (the only place a real model comparison exists):**

| Rank by PR-AUC | Model | PR-AUC | ROC-AUC | F1 | Shippable on device? |
|---|---|---:|---:|---:|---|
| 1 | Hist gradient boosting | **0.8150** | **0.9459** | **0.7270** | **No** — cannot be walked by `DecisionForest`; does not fit the size budget |
| 2 | Random forest (shipped, calibrated) | 0.7646 | 0.9368 | 0.7080 | **Yes** |
| 3 | Logistic regression | 0.6596 | 0.9044 | 0.6085 | No (different runtime) |
| 4 | Majority class | 0.1286 | 0.5000 | 0.0000 | — |

**The shipped model is not the best model available, and that is recorded on purpose.** Anyone
comparing against 0.8150 should know a better offline model exists in the same repository and can be
produced with one command.

On UCI HAR the comparison is a size/accuracy trade-off, not a leaderboard: 561 features → 0.9287
(not shippable at 561 features on a phone), 24 features → 0.8351 (shipped), 1 tree → 0.7967.

### G.3 Selection rationale, including deployment constraints

| Decision | Rationale |
|---|---|
| Shipped future-risk forest = 120 trees / depth 11, **not** the 300-tree winner | The **2,000,000-byte budget is a deployment constraint** (the forest is an app asset walked in Kotlin). Only **1 of 65** configurations fit. 300 trees unlimited = 20,059.7 KB / 700,492 nodes (rejected); 300 trees depth 8 = 2,003,677 bytes (over by 3,677); shipped = 1,968,314 bytes / 66,894 nodes. **Cost of the constraint: −0.031 validation PR-AUC** (0.8759 → 0.8451). The selected configuration is *both better and smaller* than the smallest 300-tree export, so the budget was better spent on depth than tree count. The rule is stored in `tuning.json` and re-derived by a test. |
| HAR activity = 24 time-domain features, 32 trees | The phone cannot compute the 561 features (FFT energy bands, entropy) in real time. Measured cost: 0.0936 accuracy. Measured benefit over a single tree: 0.0384. |
| Hand-written JSON forest walker, no TFLite/PyTorch | The two HAR models together are under 330 KB. **Zero new runtime dependencies**, and the walker is proven against scikit-learn on both sides (Python and Kotlin). |
| On-device inference for all three shipped models | A prediction that needs the network fails exactly when a tourist is out of signal, and no accelerometer stream ever leaves the phone. |
| Platt calibration on the future-risk model | The forest's vote fraction is not a probability; calibration cut the Brier score from 0.062600 to 0.056675. |
| Unsupervised trajectory scoring | GeoLife has no labels, so a supervised model would require inventing the target. |

### G.4 Validity threats, examined

**Data leakage.** Checked, not asserted, on every supervised model:

- *Future-risk:* window `[t−49, t]` and label `(t, t+50]` are disjoint **by construction**, re-derived
  from the raw recordings by a test; max end index 266; leads confined to 10–50. Second control: the
  **shuffled-label run scores ROC-AUC 0.47743 / PR-AUC 0.129885** — chance.
- *HAR and impact models:* **subject-disjoint** splits (21/9 with overlap 0), which is the only
  honest split for body-worn sensor data.
- *Transport model:* subject-disjoint (45/19 users).
- *Trajectory anomaly:* grouped by `traj_uid`, so the 847 colliding `.plt` names cannot leak across
  users; measured overlap 0.
- *Impact detector:* the shuffled-label control collapses to 0.5198, so the 0.9999 is not an artefact
  of leakage — but the **positives share a synthetic generator**, which is a *different* and
  unresolvable validity threat (G.4, below).

**Class imbalance.** UniMiB 12.63 % positive; GeoLife transport modes range from 6,460 (`walk`) to 2
(`motorcycle`). Handled with balanced class weights and reported with per-class support, macro
averages, PR-AUC and the majority-class baseline in the same table. Accuracy is never the sole
headline where classes are unbalanced.

**Subject dependence.** The reason for every subject-disjoint split. The cost is that each supervised
model is evaluated on **one** held-out fold — e.g. LAYING's perfect 1.0 is one fold of thirty
subjects. Repeated cross-validation was **not performed** (`NOT AVAILABLE`).

**Overfitting.** The trajectory model's train and test score distributions match closely
(mean 0.4141 vs 0.4080). The HAR and future-risk models are evaluated on unseen subjects. The
future-risk model is *under*-fitted relative to the repository's best model, and that is documented
rather than hidden.

**Threshold trade-offs.** The future-risk HIGH threshold (0.314046) maximises validation F1 and buys
recall at the cost of precision: 0.7498 recall against 0.6707 precision, producing 493 false positives
against 335 false negatives. Lowering the threshold raises recall and the false-alarm rate together —
**which is precisely why the band is informational and never an alarm.**

**Offline dataset results vs live application performance.** These are **not the same thing**, and the
gap is measured nowhere:

- No instrumented or on-device run happened (`NOT CONDUCTED`) — the Kotlin walk is proven against
  scikit-learn's numbers, but the whole chain (sensor → window → features → forest → card) has never
  run on real hardware here.
- UniMiB is a lab protocol on 30 healthy adults; UCI HAR is a scripted protocol with a waist-mounted
  phone. Neither resembles a tourist with the phone in a bag, on a cable car, or in a moving vehicle.
- Offline, the classes are balanced by construction; live, they are not.

**The synthetic-positive threat (impact detector).** The 0.9999 ROC-AUC measures detection of an
**injected** signature. Because all positives share a generator, this number cannot transfer to real
falls. It is reported with that caveat attached everywhere it appears.

**Fabrication risk, actively avoided.** Where the project could not compute something, it says so:
the anomaly model ships a `warning` field saying no accuracy/F1 is reported and that the score is not a
probability; the impact report ships a `caveat` field saying the metrics are not a clinical claim; the
`metrics.json` file carries `supervised_metrics_available: false` with the reason.

### G.5 Does any of this represent a real tourist environment?

**No.** Stated as plainly as required:

- Public activity datasets and laboratory data **do not automatically validate real-world tourist
  safety.** Nothing here was recorded from a tourist, outdoors, in weather, at altitude, with a bag,
  or from a moving vehicle.
- No real incident, injury, crime or medical outcome exists in any dataset used. The single
  outcome-bearing dataset (UniMiB SHAR) carries **falls in a laboratory**.
- No user was ever asked to use the system, and no device ever rendered a screen (`NOT CONDUCTED`).
- Therefore: every number in this report is a statement about **models on held-out laboratory and
  public data**. None of them is a statement about a tourist.

---

## H. Evidence appendix

### H.1 Dataset inventory and citations

| Dataset | Citation | Files on disk | Redistributed here? |
|---|---|---|---|
| GeoLife GPS Trajectories 1.3 | Zheng, Y., Xie, X., Ma, W.-Y. et al., Microsoft Research Asia — https://www.microsoft.com/en-us/download/details.aspx?id=52367 | `ml/data/geolife/Geolife Trajectories 1.3/Data` (18,670 `.plt`), plus 69 `labels.txt` | **No** (raw data not redistributed) |
| UCI HAR | Reyes-Ortiz et al., UCI ML Repository id `uci-240` | `ml/data/har/extracted/UCI HAR Dataset` | No |
| UniMiB SHAR | Micucci, D., Mobilio, M., Napoletano, P. (2017), arXiv:1611.07688v2 | `ml/data/unimib/UniMiB-SHAR/`, archive SHA-256 `365a3967…67160` | **No** — only derived artefacts (weights, aggregate metrics, 16 reference windows) |
| OpenStreetMap | © OpenStreetMap contributors, **ODbL** | `ml/data/osm/tiananmen_1p5km_overpass.json` | cache only |
| Weather | Open-Meteo historical archive | `ml/data/weather/` | cache only |
| Synthetic fixture | project-generated | `ml/data/synthetic_sample/` | yes, clearly labelled |

### H.2 EDA output inventory

- **GeoLife** — `ml/outputs/eda/`: `fig_01`…`fig_12` (lat/lon scatter, density, length, duration,
  sampling interval, speed, dwell, time-of-day, spatial trajectories, boxplots, correlation heatmap,
  transport balance); `table_01`, `01b`, `02`, `02b`, `03`, `04`, `05`, `06`, `07`, `08`, `09`, `10`;
  `findings.md`. Plus `ml/outputs/clean/`: `trajectory_summary.csv` (4.1 MB, 18,670 rows),
  `points_clean_sample.csv` (227.9 MB, 1,429,740 rows), `preprocess_meta.json`, `sample_manifest.json`.
- **UCI HAR** — `ml/outputs/har/eda/`: `fig_har_eda_01`…`06`; `table_har_eda_class_balance`,
  `_correlation_subset`, `_gravity_magnitude`, `_signal_stats`, `_subject_split`; `findings.md`.
  Plus `ml/outputs/har/clean/`: `har_dataset_meta.json`, `har_class_balance.csv`,
  `har_subject_split.csv`, `har_feature_names.csv`, `har_window_summary.csv`.
- **OSM** — `ml/outputs/osm/`: `fig_01`…`fig_06`; `table_01`…`table_07`; `findings.md`.
- **Weather** — `ml/outputs/weather/`: `fig_01`…`fig_04`; `table_01`…`table_04` (per site);
  `findings.md`.
- **History / zone crossings** — `ml/outputs/history/`: `fig_01`…`fig_06`;
  `table_01_user_stats` … `table_10_zone_definition`; `findings.md`.
- **Anomaly evaluation** — `ml/outputs/eval/`: `eval_report.json`, `eval_metrics.csv`,
  `top_anomalies.csv`, `injection_scores.csv` (30.1 MB), `findings.md`.
- **Transport** — `ml/outputs/transport/`: `fig_01`…`fig_03`; `table_01_dataset.csv` (1.8 MB),
  `table_02_per_class.csv`, `table_03_confusion.csv`, `table_04_split.csv`; `model_metrics.json`;
  `model_card.md`; `findings.md`.
- **Future risk** — `ml/outputs/future_risk/figures/`: `fig_01_precision_recall.png`,
  `fig_02_roc.png`, `fig_03_calibration.png`, `fig_04_confusion_matrix.png`, `fig_05_lead_time.png`,
  `fig_06_example_windows.png`.

### H.3 Model artifact inventory

| Artifact | Bytes | Version | Where it is used |
|---|---:|---|---|
| `ml/models/isolation_forest.joblib` | 1,753.5 KB | — | offline analysis only (`ml/predict.py`) |
| `ml/models/model_meta.json` | 1.2 KB | — | metadata |
| `ml/models/split.json` | 31.9 KB | — | the recorded train/test split |
| `app/src/main/assets/har_activity_model.json` | 268,734 | — | shipped, on-device |
| `app/src/main/assets/har_fall_model.json` | 59,679 | — | shipped, on-device |
| `app/src/main/assets/har_activity_model_spec.json` | 3,479 | — | shipped documentation asset |
| `app/src/main/assets/future_risk_model.json` | **1,974,297** | **`future-risk-unimib-v1`** | shipped, on-device |
| `app/src/test/resources/future_risk_reference_cases.json` | 28.6 KB | — | Kotlin parity test (16 windows) |
| `app/src/test/resources/har_reference_cases.json` | 199.1 KB | — | Kotlin parity test (12 windows) |
| `app/src/test/resources/har_fall_reference_cases.json` | 295.6 KB | — | Kotlin parity test (12 windows) |

A test asserts the app asset is **byte-identical** to the verified training output, so the shipped
model cannot drift from the trained one.

### H.4 Actual training / evaluation commands

```powershell
# from the repository root (Windows, Python 3.11.9)

# GeoLife: preprocess -> EDA -> train -> evaluate
python ml\preprocess.py
python ml\eda.py
python ml\train.py
python ml\evaluate.py

# UCI HAR: extract/verify -> activity model -> injected-impact detector -> export check
python ml\har_preprocess.py
python ml\har_train.py
python ml\har_fall.py
python ml\har_verify_export.py        # expect ALL PASS

# Analysis stages
python ml\har_eda.py
python ml\history.py
python ml\osm_eda.py                  # uses cache; --refresh to re-fetch
python ml\weather_eda.py              # Open-Meteo unless OPENWEATHER_API_KEY is set
python ml\transport_mode.py

# Future-risk model: train, then the 65-config size-budget search
python ml\future_risk\train.py
python ml\future_risk\tune.py

# Everything, or the opt-in stages
powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1 -Network -Transport -FutureRisk

# The ML test suites
python -m pytest ml\tests -q
python -m pytest ../ml/tests ../ml/future_risk/tests -q
```

### H.5 Verified metrics and their source files

| Metric | Value | Source file |
|---|---|---|
| Trajectory anomaly ROC-AUC (injection) | 0.8921805095044446 | `ml/outputs/eval/eval_report.json` |
| Trajectory anomaly PR-AUC | 0.6762856705182372 | same |
| Trajectory anomaly shuffled control | 0.5006854966369488 | same |
| Trajectory anomaly baseline threshold | 0.5298659669265341 → 2.00 % | same + `eval_metrics.csv` |
| Anomaly score train/test means | 0.41405412442585776 / 0.4079985308011531 | `ml/outputs/train/train_report.json` |
| HAR on-device accuracy / macro-F1 | 0.835086528673227 / 0.8332863949345272 | `ml/outputs/har/train/har_train_report.json` |
| HAR 561-feature accuracy / macro-F1 | 0.9287410926365796 / 0.9270658436580382 | same |
| Impact detector ROC-AUC / AP / F1 | 0.9999156687468376 / 0.9999188861127803 / 0.9962141698215251 | `ml/outputs/har/fall/har_fall_report.json` |
| Impact detector shuffled control | 0.5197569385881075 | same |
| Future-risk ROC-AUC / PR-AUC (shipped) | 0.936848 / 0.764569 | `ml/outputs/future_risk/metrics.json` |
| Future-risk precision / recall / F1 | 0.6707 / 0.7498 / 0.7080 | same |
| Future-risk accuracy / Brier | 0.9205 / 0.056675 | same |
| Future-risk trial sensitivity | 0.929577 (264/284) | same |
| Future-risk false alarms / min (app cadence) | 5.092414 | same |
| Future-risk shuffled-label control | ROC-AUC 0.47743 / PR-AUC 0.129885 | same |
| Transport accuracy / macro-F1 / weighted-F1 | 0.8475452196382429 / 0.578396301098501 / 0.8506561293918022 | `ml/outputs/transport/model_metrics.json` |

### H.6 Confusion matrices and classification reports

| Model | Saved as | Notes |
|---|---|---|
| Future-risk | counts in `metrics.json`; figure `fig_04_confusion_matrix.png` | full 2×2 saved numerically |
| Impact detector | figure `fig_har_fall_02_confusion.png` | counts **derived** from saved precision/recall (D.3) |
| HAR activity (24-feature) | figure `fig_har_03_confusion_deployable.png` | not saved numerically |
| HAR activity (561-feature) | figure `fig_har_02_confusion_561.png` + full per-class report in `har_train_report.json` | classification report saved |
| Transport | `table_03_confusion.csv` (real 11×11) + `table_02_per_class.csv` | full numeric confusion saved |
| Trajectory anomaly | `top_anomalies.csv` (ranked real anomalies) | no confusion — unsupervised |

### H.7 Test logs and measured test counts

| Suite | Command | Executed | Passed | Failed | Errors | Skipped |
|---|---|---:|---:|---:|---:|---:|
| Android JVM unit (JUnit4 + Robolectric) | `:app:testDebugUnitTest` | **548** | **541** | 0 | 0 | 7 |
| Authority backend (pytest, in-process) | `python -m pytest backend/tests -q` | **265** | **265** | 0 | 0 | 0 |
| Authority backend acceptance (real subprocess) | `python backend/verify_integration.py` | **41** | **41** | 0 | 0 | 0 |
| Python / ML (pytest) | `python -m pytest ml/tests ml/future_risk/tests -q` | **179** | **179** | 0 | 0 | 0 |
| Android instrumented (`androidTest`) | `:app:compileDebugAndroidTestKotlin` | 0 (compile only) | — | — | — | — |

- The **7 skipped** Android cases are the opt-in live-backend tests (`AuthorityLiveBackendTest`),
  which run only with `-Dtsm.e2e.baseUrl`. Run against a real Flask server they were
  **7/7 passed, `skipped="0"`** (`docs/test_report.md` §7.1).
- The 179 ML tests are `ml/tests` (86) + `ml/future_risk/tests` (93).
- Instrumented Android tests were **never executed** — no device or emulator existed.

### H.8 Documented inconsistencies and open items found during this audit

Reported rather than smoothed over:

1. **`docs/dataset_and_eda.md` §10 is stale.** It states *"Future-risk prediction: not built, on
   purpose"*, on the reasoning that none of the four named datasets carries an outcome label. That
   reasoning predates the **UniMiB SHAR** work: a future-event model **is** built, shipped
   (`app/src/main/assets/future_risk_model.json`, version `future-risk-unimib-v1`) and documented in
   `docs/future_risk_prediction.md`. The distinction that keeps both statements partly true is that
   UniMiB is a *fifth* dataset and the model predicts a **1-second fall impact**, not a
   30-minute tourist-incident risk. **`docs/dataset_and_eda.md` §10 needs a correction.**
2. **Test-count discrepancy.** `docs/future_risk_prediction.md` §16's inline comment says *"88
   pipeline tests"* while §14 and the measured total say **93**. The measured run is 179 = 86 + 93.
   The inline comment is stale.
3. **Transport macro-F1 appears not to match its per-class table.** The saved macro-F1 (0.5784) is the
   mean over the **10** classes with test support; `airplane` has support 0 and is excluded. Averaging
   all 11 printed per-class F1 values gives 0.5258. **Resolved above (D.5); not an error**, but worth
   recording because it looks like one.
4. **GeoLife trajectory-count discrepancy.** The bundled user guide claims 17,621 trajectories; the
   archive physically contains **18,670** `.plt` files. Both are reported; the discrepancy is
   upstream, not ours.
5. **`docs/model_card_movement.md` §3's 0.7581-vs-0.8351 feature ablation** is a **development
   measurement** recorded in `docs/progress_log.md`, not regenerated by `har_train.py`. Treated as
   such here and not cited as a shipped artefact.

### H.9 Unavailable, unverified and not-performed

**`NOT AVAILABLE` (data or artefact does not exist):**

- Supervised metrics (accuracy / F1 / confusion / real ROC-AUC) for the trajectory anomaly model —
  GeoLife has no anomaly labels.
- ROC-AUC / PR-AUC / per-class specificity / calibration for the HAR activity model — never saved.
- Training window count for the impact detector — only the 1,848 test windows were saved.
- ROC-AUC / PR-AUC and calibration for the transport-mode model — never saved.
- Repeated / cross-validated subject-disjoint evaluation for any supervised model — single fold only.
- Per-stage training wall-clock for `har_train.py` and `har_fall.py` — not recorded separately.
- Anomaly detection performance on any anomaly family other than GPS teleports.
- Any model trained on a real tourist incident, injury, crime or medical outcome — none exists.

**`NOT CONDUCTED` (never performed):**

- Any user study, interview, survey, diary study or usability evaluation.
- Any on-device or instrumented test run (no emulator, no device; `adb devices` empty).
- Any outdoor, weather, bag-carry, vehicle or altitude testing.
- Phone-drop testing (no phone-drop class exists in any dataset used).
- Clinical, medical or emergency-services validation.
- End-to-end validation of the app's own geofence model on a real boundary crossing by a real person.

**`N/A` (not applicable):**

- ROC-AUC / PR-AUC for the unsupervised trajectory score as a *real-anomaly* metric.
- Calibration for the rule-based geofence and the `ApproachRisk` heuristic — neither is a model and
  neither has a probability.
- Threshold analysis for multiclass argmax models.

---

*Every number in this document was read out of a file in `ml/outputs/`, `ml/models/` or
`app/src/main/assets/` during this audit, or derived from those values by the arithmetic shown. Where
evidence does not exist, this document says so instead of supplying a number.*
