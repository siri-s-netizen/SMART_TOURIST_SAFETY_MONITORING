# Data, models and evaluation (`ml/`) — consolidated report

*Generated 2026-10-19. Everything in this report was built in this project. Every number was written
by a script that ran and was read back out of the file it produced — none is estimated.*

**Full per-model detail:** [`docs/dataset_and_eda.md`](dataset_and_eda.md) ·
[`docs/model_card.md`](model_card.md) ·
[`docs/model_card_movement.md`](model_card_movement.md) ·
[`docs/future_risk_prediction.md`](future_risk_prediction.md) ·
[`docs/test_report.md`](test_report.md)
**Plain English:** [`docs/data_ml_explained_simply.md`](data_ml_explained_simply.md)

---

## 1. What was built

| Component | Trained? | Method | Artifact | Runs where |
|---|---|---|---|---|
| **Trajectory anomaly detector** | Unsupervised | `IsolationForest`, 200 trees, 14 features | `ml/models/isolation_forest.joblib` (1,753.5 KB) | `ml/` analysis |
| **Activity recognition** | Yes | `RandomForest`, 32 trees, depth 8, 24 features | `app/src/main/assets/har_activity_model.json` (268,734 B) | **On the phone** |
| **Impact (fall-signature) detector** | Yes | `RandomForest`, 40 trees, 24 features | `app/src/main/assets/har_fall_model.json` (59,679 B) | **On the phone** |
| **Future-risk predictor** | Yes | `RandomForest`, 120 trees, depth 11, 22 features + Platt calibration | `app/src/main/assets/future_risk_model.json` (1,974,297 B), version `future-risk-unimib-v1` | **On the phone** |
| **Transport-mode classifier** | Yes | `RandomForest`, 300 trees, 9 features | `ml/outputs/transport/` | `ml/` analysis (not shipped) |
| **Geofence** | No — configured rule | Polygon test + event log | `app/` + `backend/` | Phone + server |
| **Approach-risk score** | No — heuristic | Published weighted checklist | `app/` | Phone |

Three models are exported to JSON and walked by hand-written Kotlin **on the device**, with no
TensorFlow, TFLite or PyTorch, and no network call at inference time. The two HAR models together are
under 330 KB, which is why a hand-written walker was chosen over a runtime.

---

## 2. Datasets used

### 2.1 Inventory

| Dataset | What it actually is | Scale (measured) | Licence / terms | Used for |
|---|---|---|---|---|
| **GeoLife GPS Trajectories 1.3** | GPS traces from volunteers, 2000–2012, mostly Beijing | Archive 313,164,406 B; **182 users; 18,670 `.plt` files; 24,876,978 data rows; 0 malformed** | **No OSI licence bundled** — citation-only, research use | EDA, anomaly detection, transport model, zone analysis |
| **UCI HAR** | 30 volunteers, scripted activities, waist-mounted phone | **7,352 train / 2,947 test windows**, 561 features, 6 activities, 9 channels @ 50 Hz | Not documented in this repository — see the UCI dataset page | EDA, activity recognition, impact detector |
| **UniMiB SHAR** | 30 volunteers performing 9 daily activities and **8 real fall types** in a lab | Archive 266,206,080 B, SHA-256 `365a3967…67160`; 1,980 trials read, **1,979 used** @ 50 Hz | Publicly distributed for academic research; **no explicit OSI licence** | The future-risk model |
| **OpenStreetMap** (Overpass API) | Live public map database, bounded extract | **15,980 elements** in 1.5 km × 1.5 km of Tiananmen, Beijing | © OpenStreetMap contributors, **ODbL** | Map context EDA only — **no model input** |
| **Open-Meteo** weather archive | Historical weather for two sites | **574 days**, 2011-01-01…2012-07-27 | Free/open archive | Context EDA only — **no model input** |
| **Synthetic fixture** | Project-generated stand-in | tiny | project-internal | Plumbing test when the real archive is absent |

### 2.2 The facts that shape everything downstream

- **GeoLife contains no danger, injury or incident labels.** Its only labels are transportation modes
  (69 users, 14,718 segments). A supervised risk model is therefore impossible, and the trajectory
  model is **unsupervised** — its output is a movement *anomaly score*, never a probability of danger.
- **UCI HAR contains no falls at all** — no fall class, no impact annotation. The impact detector's
  positive class was therefore **manufactured** by injecting a fall-like transient into real held-out
  recordings. Every metric for that model measures detection of the **injected signature**.
- **UniMiB SHAR is the only dataset here with a real recorded outcome** (a real fall). That is why the
  future-risk model is the only one that predicts a *future event* rather than describing the present.
- **The weather provider actually used is Open-Meteo, not OpenWeatherMap.** The brief named
  OpenWeatherMap, which needs a key we did not have; the fallback is stated on every artefact.
- **Two corrections are recorded rather than hidden:** 698,900 duplicate `(trajectory, timestamp)` rows
  were removed from GeoLife, and **one corrupt UniMiB recording** (subject 10, `Jumping`, trial 1 —
  9,369 non-finite values of 9,624) was **dropped and counted**.

### 2.3 Dataset → model mapping

| Dataset | Anomaly model | Activity model | Impact detector | Future-risk | Transport | EDA | Map |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| GeoLife 1.3 | **✔ train + test** | – | – | – | **✔ labels** | **✔** | **✔** |
| UCI HAR | – | **✔ train + test** | **✔ train + test** | – | – | **✔** | – |
| UniMiB SHAR | – | – | – | **✔ train + test** | – | ✔ | – |
| OpenStreetMap | – | – | – | – | – | **✔** | **✔** |
| Open-Meteo | – | – | – | – | – | **✔** | – |

---

## 3. EDA performed

### 3.1 GeoLife — the twelve analyses

Point-level figures use a fixed-seed sample of **1,429,740 points / 1,200 trajectories**; length,
duration and per-user figures use all 18,670 trajectories. Outputs: `ml/outputs/eda/` (12 figures,
11 tables, `findings.md`).

| # | Analysis | Measured result |
|---|---|---|
| 1 | Shape / dtypes | 1,429,740 × 15 (point sample); 18,670 × 22 (trajectory summary) |
| 2 | Missing / duplicates | 698,900 duplicate rows, 1 invalid coordinate, 0 unparsable, 0 malformed; `dt_s` missing = 1,200 (structural) |
| 3 | Spatial extent | lat 1.27681…51.10710, lon −179.69134…179.99330; density overwhelmingly Beijing |
| 4 | Length / duration | points median **504**, mean 1,295, max 92,645; duration median **2,528 s**, max 348,431 s; total distance **1,021,454 km** |
| 5 | Sampling interval | median `dt` **2.00 s**, mean 4.74, p95 5.00, max 3,595; **96.3 %** in the 1–5 s band |
| 6 | Speed | 4,525 of 1,423,352 steps > 60 m/s **excluded**; speed mean **6.55 m/s**, median 3.23, p95 20.72 |
| 7 | Dwell / stops | **557** stops (speed < 0.5 m/s for ≥ 120 s); median 174 s, max 9,945 s |
| 8 | Time of day | busiest **09:00** (108,135 fixes); quietest **21:00** (11,095); **no timezone stored** |
| 9 | Spatial plot | 60 sample trajectories |
| 10 | Outliers | `alt_change` 47.55 % flagged, `straightness_w5` 14.86 %, `accel_mps2` 13.45 %, `turn_deg` 12.26 %, `altitude` 11.21 % |
| 11 | Correlation | strongest pair `speed_mps ~ speed_mean_w5`, **r = 0.979** (same motion, different window) |
| 12 | Class balance | 14,718 transport labels across 69 users: walk 6,460 · bus 2,853 · bike 2,089 · taxi 1,179 · car 993 · subway 813 · train 299 · airplane 17 · boat 7 · run 6 · motorcycle 2. **No danger labels exist.** |

**Critical data quirk found:** `.plt` file names **collide across users** — 847 ids are shared. Every
grouping therefore uses `traj_uid` (user id + file stem); grouping on the bare file name would silently
merge different people's trajectories, a leakage bug.

### 3.2 UCI HAR

- **Shape:** 561 features per window; 7,352 train / 2,947 test windows; 6 activities; raw window 128
  samples @ 50 Hz (2.56 s), 9 inertial channels.
- **Split:** 21 train / 9 test subjects, **overlap 0** — genuinely subject-disjoint.
- **Class balance:** near-uniform (train 986–1,407; test 420–537 per activity). Accuracy is therefore a
  legitimate headline; no resampling or class weighting was needed.
- **Gravity sanity check:** mean total-acceleration magnitude **1.0323 g** (per-activity 1.0075–1.0516 g)
  — a real measurement centring on gravity. This is what justifies converting live m/s² samples to g.
- **Signal bands:** the three walking variants overlap heavily; sitting/standing/lying separate cleanly.
  This predicted the eventual confusion pattern exactly.
- **Feature redundancy:** the 561 features are heavily correlated; accuracy flattens past ~50 features.

### 3.3 UniMiB SHAR — the window and label construction

| Quantity | Measured |
|---|---|
| Trials in / kept | 1,980 / **1,979** (1 corrupt recording dropped) |
| Fall trials used | 1,440 (6 excluded — never reached the 2.0 g impact threshold) |
| ADL trials | 539 |
| Windows cut | **51,128** (50-sample window, 10-sample stride) |
| Positives | **6,458** → positive rate **12.63 %** |
| Impact peak range | **2.0299 g … 3.4562 g** |
| Lead-time histogram (windows per lead) | 50: 923 · 40: 1,268 · 30: 1,412 · 20: 1,425 · 10: 1,430 |

**Why the horizon is one second — measured, not chosen:** fall trials run 151–446 samples (median 174 =
3.5 s) with the impact near index ~120, leaving ≥ 95 samples of pre-impact signal. A 50-sample window
ending at least one sample before impact fits in **every** included trial, so 1.0 s is the **longest
fully supported** horizon.

**Class imbalance is real and severe** (12.63 % positive). Hence `class_weight="balanced_subsample"`,
and precision / recall / PR-AUC reported alongside accuracy everywhere — a "always no" model already
scores 87.1 % accuracy, and the PR-AUC baseline (0.128565) *is* the positive rate.

### 3.4 Map, weather and historical trends

- **OpenStreetMap** (`ml/outputs/osm/`): 12,783 nodes, 3,197 ways, 0 relations = **15,980 elements**,
  **5,681.4 nodes/km²**. Top tags: `building` 1,205 · `highway` 1,075 · `height` 800. Highway classes:
  footway 504, pedestrian 114, steps 104 (17 distinct). Amenity classes: restaurant 36, toilets 22,
  café 12 (22 distinct). Fetched 2026-10-17T13:39:46Z, cached, and reproducible from the recorded query.
- **Weather** (`ml/outputs/weather/`): 574 days, two sites. `geolife_centroid` (39.9770 N, 116.3501 E,
  the **component-wise median** of the cleaned sample) → mean 12.17 °C, precip 806.4 mm, 175 harsh days
  (30.5 %). `huangshan_trek` (30.134 N, 118.161 E, an analyst-chosen trekking point) → mean 7.07 °C,
  precip 2,676.3 mm, 218 harsh days (38.0 %). Harsh-day rule: precip ≥ 10 mm **or** min T ≤ −5 °C **or**
  max T ≥ 35 °C **or** mean wind ≥ 30 km/h.
- **Historical / zone trends** (`ml/outputs/history/`): 18,670 trajectories / 182 users spanning
  2000-01-01…2012-07-27. Trajectories per user median 28, max 2,153. Busiest hour 09:00, busiest weekday
  Saturday, peak month 2008-11. **557 stops, revisit rate 0.487**, most-revisited single place 19 stops.
  A zone derived from data density (densest 0.02° cell 39.9900 N/116.3300 E, expanded ±0.03° →
  S 39.95, W 116.29, N 40.03, E 116.37) is crossed by **431** of the 785 intersecting trajectories:
  **430 entries / 426 exits over 58 months**.

### 3.5 What the EDA changed

| Finding | Consequent change |
|---|---|
| `.plt` names collide across users (847) | composite `traj_uid` grouping — prevents silent cross-user leakage |
| Irregular, gap-ridden sampling (1–3,595 s) | 120 s segment split; `log_dt_s` as a feature; no resampling |
| GPS jumps (4,525 speeds > 60 m/s) | jumps kept as a data property; the evaluation injects teleports of the same family |
| `altitude` unreliable (47.55 % IQR-flagged) | documented as unreliable; no conclusion depends on it |
| Structural missingness (1,200 `dt_s`) | first point of each segment dropped, never imputed |
| HAR classes near-balanced | accuracy used as the headline; no resampling |
| HAR walking variants overlap | the app groups the three as "ambulating" and never acts on the distinction |
| 561 HAR features redundant | 24-feature on-device subset, with its measured 0.0936 accuracy cost reported |
| UniMiB 12.63 % positive rate | balanced class weights; precision/recall/PR-AUC reported everywhere |
| UniMiB impact always ≥ 2.0 g | 2.0 g reused as the shared "hard landing" threshold in code and label rule |
| GeoLife has no danger labels | the trajectory model is unsupervised and never called a probability |

### 3.6 Figures from the EDA

![Trajectory length histogram](../ml/outputs/eda/fig_03_traj_length_hist.png)
![Sampling interval histogram](../ml/outputs/eda/fig_05_sampling_interval_hist.png)

*Length (median 504 points, heavy right tail) and sampling interval (96.3 % of gaps in 1–5 s). Both drove
the choice of windowed features and a gap-based segment split rather than fixed-rate resampling.*

![Speed histogram](../ml/outputs/eda/fig_06_speed_hist.png)
![Time of day](../ml/outputs/eda/fig_08_time_of_day.png)

*Speed after removing 4,525 impossible jumps (mean 6.55, median 3.23 m/s) — a plausible human range. The
daily rhythm peaks at 09:00, which is why `tod_sin`/`tod_cos` are model features.*

![Correlation heatmap](../ml/outputs/eda/fig_11_correlation_heatmap.png)
![Transport mode balance](../ml/outputs/eda/fig_12_transport_mode_balance.png)

*The strongest correlation is `speed_mps ~ speed_mean_w5` at r = 0.979 (harmless for an unsupervised
scorer, documented rather than hidden). Transport labels are severely imbalanced — walk 6,460 down to
motorcycle 2 — which is why the transport model uses balanced class weights.*

![HAR class balance](../ml/outputs/har/eda/fig_har_eda_01_class_balance.png)
![HAR gravity magnitude](../ml/outputs/har/eda/fig_har_eda_05_gravity_magnitude.png)

*UCI HAR's near-uniform classes justify accuracy as a headline. The gravity magnitude centres on
1.0323 g — a real accelerometer reading gravity, which is what justifies the m/s² → g conversion.*

![Zone crossing trend](../ml/outputs/history/fig_04_zone_crossings_trend.png)

*430 entries / 426 exits over 58 months in a data-derived Beijing zone: real evidence that boundary
crossings happen at a countable rate.*

---

## 4. The models and what they measured

### 4.1 Trajectory anomaly detector (GeoLife, unsupervised)

`IsolationForest`, 200 trees, contamination 0.02, seed 42, over 14 standardised movement features. Score
= `-score_samples(scaled_features)`; **higher = more anomalous, not a probability**. Trained on
1,060,493 points (900 trajectories), evaluated on 369,247 points (300 trajectories), split by
`traj_uid` with **0 overlap**.

**Accuracy, F1 and a confusion matrix do not exist for this model, and can't:** GeoLife has no anomaly
labels, so there is nothing to score against. Any such figure would be invented.

So it was validated by **injection on real data**: GPS teleport bursts (0.02°, run length 3) planted
into real held-out trajectories.

| Quantity | Value |
|---|---|
| Points marked affected | 17,462 |
| ROC-AUC | **0.8922** |
| Average precision (PR-AUC) | **0.6763** |
| Precision@k / Recall@k (k = 17,462) | **0.6165** / **0.6165** |
| **Label-shuffle control ROC-AUC** | **0.5007** (chance — the metric is meaningful) |

Thresholds are score quantiles, not tuned: 0.98 → **0.5299** → **2.00 %** flagged (0.90 → 0.4588 → 10 %;
0.95 → 0.4853 → 5 %; 0.99 → 0.5810 → 1 %; 0.995 → 0.6374 → 0.5 %). Score distribution: train mean
0.4141 / std 0.0479, test mean 0.4080 / std 0.0437 — train and test match, so the model is not
memorising the training split.

### 4.2 Activity recognition (UCI HAR)

Official subject-disjoint split: 7,352 train / 2,947 test windows, 21 / 9 subjects, overlap 0.

| Model | Features | Trees | Accuracy | Macro-F1 |
|---|---:|---:|---:|---:|
| Reference (full detail) | 561 | 300 | 0.9287 | 0.9271 |
| **On-device (shipped)** | **24** | **32** | **0.8351** | **0.8333** |
| Single decision tree (baseline) | 24 | 1 | 0.7967 | 0.7943 |

Per-class, shipped 24-feature model:

| Activity | Precision | Recall | F1 | Support |
|---|---:|---:|---:|---:|
| WALKING | 0.7363 | 0.8669 | 0.7963 | 496 |
| WALKING_UPSTAIRS | 0.7380 | 0.7176 | 0.7277 | 471 |
| WALKING_DOWNSTAIRS | 0.9507 | 0.7810 | 0.8575 | 420 |
| SITTING | 0.7996 | 0.8045 | 0.8020 | 491 |
| STANDING | 0.8185 | 0.8139 | 0.8162 | 532 |
| LAYING | 1.0000 | 1.0000 | 1.0000 | 537 |
| **Macro average** | **0.8405** | **0.8307** | **0.8333** | **2,947** |

The cost of shipping the small model is **0.0936 accuracy**; the benefit over a single tree is **0.0384**.
Both are reported because either alone misleads. The failures are concentrated in the three walking
variants — which the app groups as one "ambulating" state and never acts on separately.

### 4.3 Impact (fall-signature) detector

| Metric | Value |
|---|---:|
| Test windows | 1,848 (924 positive / 924 negative), subject-disjoint |
| ROC-AUC | **0.9999** |
| Average precision (PR-AUC) | **0.9999** |
| F1 / precision / recall @ 0.5 | **0.9962** / 0.9957 / 0.9968 |
| **Shuffled-label control ROC-AUC** | **0.5198** |

Confusion matrix — derived from the saved precision/recall and n = 924 per class: **TP 921, FP 4, FN 3,
TN 920** (accuracy 0.9962, specificity 0.9957).

**How to read this.** The shuffled-label control collapses to chance, so the near-perfect score is the
detector genuinely finding the **injected** signature in real held-out subjects' recordings — not
leakage. But the positives were manufactured, so **this measures detection of a planted fall, not of a
real person falling.** §7.2 states the consequence.

![Injected impact signature](../ml/outputs/har/fall/fig_har_fall_03_signal.png)

*One injected impact. A large, stereotyped, high-signal transient produced by a single generator — which
is exactly why 0.9999 cannot be read as fall-detection performance.*

### 4.4 Future-risk predictor (UniMiB SHAR, shipped)

Subject-disjoint 60/20/20: **18 / 6 / 6** subjects; 31,122 / 9,591 / 10,415 windows. Validation subjects
[12, 13, 15, 18, 24, 29]; test subjects [2, 3, 5, 9, 14, 28]. The test set was touched **once**, after
every choice was frozen.

| Model | Precision | Recall | F1 | ROC-AUC | PR-AUC |
|---|---:|---:|---:|---:|---:|
| Majority class | 0.0000 | 0.0000 | 0.0000 | 0.5000 | 0.128565 |
| Logistic regression | 0.4760 | 0.8432 | 0.6085 | 0.904389 | 0.659633 |
| Hist gradient boosting (**better, not shipped**) | **0.7916** | 0.6721 | **0.7270** | **0.945913** | **0.815000** |
| Random forest, raw vote @ 0.5 | 0.6534 | 0.7827 | 0.7122 | 0.936848 | 0.764569 |
| **Random forest, calibrated @ HIGH ≥ 0.314046 (shipped)** | **0.6707** | **0.7498** | **0.7080** | **0.936848** | **0.764569** |

Confusion matrix at the HIGH threshold: **TN 8,583 · FP 493 · FN 335 · TP 1,004**.
Accuracy **0.9205**; Brier 0.056675; specificity 0.9457.

**Calibration** — Platt scaling fitted on validation only (a = 1.24940294, b = −1.07904853). Brier
improves from 0.062600 (raw) to **0.056675** (calibrated). The top bin is close (0.9–1.0: 496 windows,
predicted 0.9407, observed 0.8952), but the **middle bins are over-confident** — the 0.3–0.4 bin
predicted 0.3461 and was positive 0.5000 of the time. The probability is shown in the app *only*
because it is calibrated, and always alongside the band, thresholds and version.

**Risk bands** (fitted on validation, then frozen): HIGH ≥ **0.314046** (the max-F1 threshold);
MODERATE ≥ **0.069201** (the largest threshold whose validation recall is still ≥ 0.95).

**Detection across the horizon** — it does not collapse as the lead grows, so there is real pre-impact
signal:

| Lead | Positive windows | Detected | Rate |
|---|---:|---:|---:|
| 0.2 s | 284 | 204 | 0.7183 |
| 0.4 s | 281 | 199 | 0.7082 |
| 0.6 s | 281 | 217 | 0.7722 |
| 0.8 s | 270 | 213 | 0.7889 |
| 1.0 s | 223 | 171 | 0.7668 |

**Trial-level sensitivity — the number that matters most:** of **284** held-out fall trials, **264** were
flagged HIGH at least once → **0.9296**. This is the honest headline; window recall (0.7498) is the
number underneath it.

**False alarms on ordinary activity:** 493 of 9,076 ADL windows → **5.09 false alarms per minute** at
the app's 0.64 s cadence (16.30/min at the raw 0.2 s stride). By activity: **jumping 16.1 %**, **sitting
down 15.6 %**, **lying down 12.7 %**, going downstairs 4.4 %, standing up from floor 3.2 %, going
upstairs 2.5 %, standing up from sofa 2.8 %, running 1.6 %, walking 1.1 %. The three worst confusions are
the three movements where a body genuinely drops under gravity.

**Leakage controls, checked rather than asserted:** every positive window ends before its own impact
(true); all leads within the horizon (true); the feature window never overlaps the label window (true);
min/max lead 10/50 samples. Second control — the **shuffled-label run** scores ROC-AUC **0.47743** /
PR-AUC **0.129885**, i.e. chance.

![Future-risk ROC](../ml/outputs/future_risk/figures/fig_02_roc.png)
![Future-risk calibration](../ml/outputs/future_risk/figures/fig_03_calibration.png)

*ROC-AUC 0.9368 looks strong; the calibration plot is the more important one — the top bin tracks the
diagonal but the mid-range sits above it.*

![Future-risk lead time](../ml/outputs/future_risk/figures/fig_05_lead_time.png)

*Detection rate from 0.2 s to 1.0 s before impact. It holds up at the full second, which is the
interesting positive result.*

### 4.5 Transport-mode classifier (GeoLife labels)

Explicitly **not** a risk model — it predicts the next segment's transportation mode.

9,584 labelled segments from 69 users; subject-disjoint split 45 / 19 users (8,036 / 1,548 segments);
`RandomForest`, 300 trees, 9 features, balanced class weights.

**Accuracy 0.8475 · macro-F1 0.5784 · weighted-F1 0.8507.**

Per-class on unseen users: walk 0.951 F1 (827) · bike 0.829 (168) · bus 0.818 (292) · subway 0.690 (117)
· car 0.588 (77) · train 0.545 (11) · taxi 0.362 (53) · motorcycle 1.000 (1) · boat, run 0.000 (1 each).
The macro-F1 is the mean over the **10** classes with test support (`airplane` has support 0). Top
features: `p95_speed_mps` 0.200, `distance_m` 0.186, `mean_speed_mps` 0.160, `straightness` 0.117.

![Activity confusion matrix (on-device)](../ml/outputs/har/train/fig_har_03_confusion_deployable.png)

*The shipped 24-feature activity model's confusion matrix: losses are almost entirely inside the walking
triad; sitting, standing and laying hold up.*

---

## 5. Training methodology (compact)

| | Trajectory anomaly | Activity | Impact detector | Future-risk | Transport |
|---|---|---|---|---|---|
| **Data** | GeoLife | UCI HAR | UCI HAR + injected impacts | UniMiB SHAR | GeoLife `labels.txt` |
| **Target** | none (unsupervised) | 6 real activity labels | injected impact vs untouched | `IMMINENT_FALL` / `NO_IMMINENT_FALL` | transport mode |
| **Features** | 14 movement features | 561 (reference) / **24** (shipped) | 24 | **22** accelerometer features | 9 segment features |
| **Window** | point-level + 5-point windows | 128 samples @ 50 Hz (2.56 s) | 128 samples | 50 samples (1.0 s), stride 10 (0.2 s) | labelled segments |
| **Horizon** | none (not a forecast) | none | none | **1.0 s** | none |
| **Split** | `traj_uid` 900 / 300 traj, 0 overlap | official subject-disjoint 21 / 9 | subject-disjoint 21 / 9 | subject-disjoint 18 / 6 / 6 | subject-disjoint 45 / 19 users |
| **Preprocessing** | standardise; segment at gaps > 120 s | dataset-provided; m/s² → g | same 24 features | **none that needs fitting** (all features are scale-free per window) | segment filtering (< 2 points or < 1 m dropped) |
| **Hyper-parameters** | 200 trees, contamination 0.02 | 300/561; **32 trees, depth 8**; 1 tree baseline | 40 trees | **120 trees, depth 11, leaf 4, sqrt, balanced_subsample** | 300 trees, leaf 2, balanced |
| **Balancing / threshold** | quantile thresholds (0.98 → 2 %) | none needed | balanced by construction; app gate ≥ 0.55 | `balanced_subsample`; HIGH 0.314046, MODERATE 0.069201; Platt calibration | balanced class weights |
| **Seed** | 42 | fixed | fixed | 42 | 42 |
| **Selection basis** | single configuration | **deployment size** (JSON asset walkable in Kotlin) | 1 configuration + shuffled control | **2,000,000-byte asset budget** (binding: only 1 of 65 configs fitted) | class balance handling |
| **Artifact** | `ml/models/isolation_forest.joblib` | `app/src/main/assets/har_activity_model.json` | `app/src/main/assets/har_fall_model.json` | `app/src/main/assets/future_risk_model.json` (1,974,297 B, `future-risk-unimib-v1`) | `ml/outputs/transport/` |

**Model selection under the size budget (future-risk).** `tune.py` searched **65 configurations** on
validation only. The winner by validation PR-AUC was rejected because it cannot ship: 300 trees
unlimited depth → val PR-AUC 0.8759 but **20,059.7 KB** / 700,492 nodes. 300 trees depth 8 → 0.8201 but
**2,003,677 bytes**, over the 2,000,000-byte budget by 3,677. The shipped 120-tree depth-11 forest →
**0.8451 / 0.9709** in **1,968,314 bytes** / 66,894 nodes. The constraint costs **−0.031 validation
PR-AUC**, and it is the reason the shipped model is not the best this pipeline can fit. The rule is
stored in `tuning.json` and re-derived by a test.

**Training-and-deployment parity (verified, not assumed).**

| Model | Max feature error | Max probability error | Argmax mismatches |
|---|---:|---:|---:|
| Activity | 5.458e-07 | 4.970e-07 | 0 |
| Impact / fall | 6.342e-07 | 4.736e-07 | 0 |
| Future-risk | parity asserted against scikit-learn on **16 held-out windows** (features, forest and calibration) | | |

A test also asserts the app asset is **byte-identical** to the verified training output, so the shipped
model cannot drift from the trained one.

**Environment:** Python **3.11.9** (Windows); numpy 2.4.4, pandas 3.0.6, scipy 1.17.1, **scikit-learn
1.8.0**, matplotlib 3.11.2, pytest 9.1.1, joblib 1.5.3. Seed **42** everywhere. A single desktop with
~8 GB RAM, no GPU. Recorded runtimes: preprocess 539.2 s · EDA 56.5 s · anomaly train 76.7 s ·
anomaly evaluate 74.01 s · HAR EDA 12.9 s · history 34.1 s · OSM 10.8 s · weather 10.6 s · transport
282.2 s · future-risk train ~4 min · future-risk tuning ~10 min.

---

## 6. Four risk concepts, kept separate

| Shown as | What it actually is | Trained? | Has a probability? |
|---|---|---|---|
| **Geofence status** | A configured rule: inside/outside a polygon the authority drew | No | No |
| **Approach risk** | A published weighted checklist over crossing history | No — documented heuristic | No |
| **Activity** | A 6-class classifier over the current 2.56 s window | Yes | Confidence only |
| **Movement / future risk** | An impact detector and a 1-second-ahead impact forecast | Yes | Yes, calibrated (future-risk) |

Enforced in code and tests: distinct types throughout, so a screen cannot render the heuristic under the
trained model's heading; the card's own text names the other concepts and denies being them (asserted by
a test); **risk never triggers SOS** — `backend/tsm/api.py` ignores risk entirely when computing state,
and a HIGH prediction leaves `latestSos` empty and `openSosCount == 0` (asserted by
`backend/tests/test_risk.py`). Predictions live in their own table, their own endpoints, and their own
dashboard panel — never the SOS queue or the incident feed.

---

## 7. Consolidated results and the limits of this evidence

### 7.1 Consolidated results

| Model / component | Dataset | Evaluation split | Sample count | Accuracy | Precision | Recall | F1 | ROC-AUC | PR-AUC | Evidence source |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---|
| Trajectory anomaly (unsupervised) | GeoLife 1.3 | trajectory-disjoint | 369,247 pts / 300 traj (17,462 injected) | — | 0.6165 (P@k) | 0.6165 (R@k) | — | 0.8922 (injection) | 0.6763 | `ml/outputs/eval/eval_report.json` |
| Activity — 561-feature reference | UCI HAR | subject-disjoint | 2,947 windows / 9 subjects | 0.9287 | 0.9301 (macro) | 0.9258 (macro) | 0.9271 | — | — | `ml/outputs/har/train/har_train_report.json` |
| **Activity — 24-feature on-device (shipped)** | UCI HAR | same | 2,947 windows | **0.8351** | 0.8405 (macro) | 0.8307 (macro) | **0.8333** | — | — | same |
| **Impact detector (shipped, synthetic positives)** | UCI HAR + injected | subject-disjoint | 1,848 windows (924/924) | 0.9962 | 0.9957 | 0.9968 | **0.9962** | **0.9999** | 0.9999 | `ml/outputs/har/fall/har_fall_report.json` |
| Future-risk — majority class | UniMiB SHAR | subject-disjoint | 10,415 windows / 6 subjects | 0.8714 | 0.0000 | 0.0000 | 0.0000 | 0.5000 | 0.128565 | `ml/outputs/future_risk/metrics.json` |
| Future-risk — hist gradient boosting (**not shipped**) | UniMiB SHAR | same | 10,415 | — | 0.7916 | 0.6721 | 0.7270 | 0.945913 | **0.815000** | same |
| **Future-risk — calibrated @ HIGH (shipped)** | UniMiB SHAR | same | 10,415 (1,339 pos) | **0.9205** | 0.6707 | **0.7498** | 0.7080 | **0.936848** | 0.764569 | same |
| Transport-mode classifier | GeoLife `labels.txt` | subject-disjoint | 1,548 segments / 19 users | 0.8475 | 0.5922 (macro) | 0.5775 (macro) | 0.5784 (macro) / 0.8507 (weighted) | — | — | `ml/outputs/transport/model_metrics.json` |

Event-level: future-risk **trial sensitivity 0.9296 (264/284)**; trajectory anomaly **group overlap
between train and test = 0**.

### 7.2 Limits of this evidence

These are properties of what **was** built, so they belong in the report rather than being cut away.

1. **Because the impact detector's positives are synthetic, its 0.9999 is not a fall-detection claim.**
   It measures detection of an injected signature.
2. **The future-risk model raises about 5.09 false alarms per minute of ordinary activity**, worst on
   jumping (16 %), sitting down (16 %) and lying down (13 %), and its mid-range probabilities are
   over-confident. It is an informational band plus a queued summary — **never** an alarm, and it never
   sends an SOS.
3. **The label is a fall impact, not a tourist incident.** UniMiB is 30 healthy adults in a laboratory;
   UCI HAR is a scripted waist-mounted protocol. No real incident, injury, crime or medical outcome
   exists in any dataset used, so no supervised tourist-risk model is possible from this data.
4. **The model cannot tell a dropped phone from a falling person** — no phone-drop class exists in any
   dataset used.
5. **Nothing here has run on a physical phone or emulator,** and the app's screens, sensors, GPS and
   HTTP stack have therefore not been exercised on hardware.
6. **No user study, interview, survey or usability evaluation was conducted** at any point.
7. **Some metrics do not exist and are not estimated:** real-anomaly accuracy/F1 for the unsupervised
   model (no labels); ROC-AUC, PR-AUC, per-class specificity and calibration for the HAR and transport
   models (never saved); repeated cross-validated subject-disjoint evaluation (single fold only); the
   impact detector's training-window count.
8. **Public and laboratory datasets do not validate real tourist safety.** Every metric here is a
   statement about models on held-out laboratory and public data — not about a tourist.

---

## 8. Evidence appendix

### 8.1 Model artifacts

| Artifact | Bytes | Version | Used by |
|---|---:|---|---|
| `ml/models/isolation_forest.joblib` | 1,753.5 KB | — | offline analysis |
| `app/src/main/assets/har_activity_model.json` | 268,734 | — | shipped, on-device |
| `app/src/main/assets/har_fall_model.json` | 59,679 | — | shipped, on-device |
| `app/src/main/assets/har_activity_model_spec.json` | 3,479 | — | shipped documentation asset |
| `app/src/main/assets/future_risk_model.json` | **1,974,297** | **`future-risk-unimib-v1`** | shipped, on-device |
| `app/src/test/resources/future_risk_reference_cases.json` | 28.6 KB | — | Kotlin parity test (16 windows) |
| `app/src/test/resources/har_reference_cases.json` | 199.1 KB | — | Kotlin parity test (12 windows) |
| `app/src/test/resources/har_fall_reference_cases.json` | 295.6 KB | — | Kotlin parity test (12 windows) |

### 8.2 Commands

```powershell
# from the repository root (Windows, Python 3.11.9)
python ml\preprocess.py            # GeoLife parse + clean
python ml\eda.py                   # 12 figures + tables
python ml\train.py                 # anomaly model
python ml\evaluate.py              # injection evaluation
python ml\har_preprocess.py        # UCI HAR extract + verify
python ml\har_train.py             # activity model
python ml\har_fall.py              # injected-impact detector
python ml\har_verify_export.py     # export vs scikit-learn: expect ALL PASS
python ml\har_eda.py
python ml\history.py
python ml\osm_eda.py               # cache; --refresh to re-fetch
python ml\weather_eda.py           # Open-Meteo unless OPENWEATHER_API_KEY is set
python ml\transport_mode.py
python ml\future_risk\train.py     # future-risk model + app asset
python ml\future_risk\tune.py      # the 65-config size-budget search

powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1   # everything offline
# -Network for the two fetches, -Transport and -FutureRisk for the two slow models

python -m pytest ml\tests ml\future_risk\tests -q
```

### 8.3 Test counts (measured, this round)

| Suite | Command | Executed | Passed | Failed | Errors | Skipped |
|---|---|---:|---:|---:|---:|---:|
| Android JVM unit | `:app:testDebugUnitTest` | **548** | **541** | 0 | 0 | 7 |
| Backend (pytest, in-process) | `python -m pytest backend/tests -q` | **265** | **265** | 0 | 0 | 0 |
| Backend acceptance (real subprocess) | `python backend/verify_integration.py` | **41** | **41** | 0 | 0 | 0 |
| Python / ML (pytest) | `python -m pytest ml/tests ml/future_risk/tests -q` | **179** | **179** | 0 | 0 | 0 |
| Android instrumented | `:app:compileDebugAndroidTestKotlin` | 0 (compile only) | — | — | — | — |

The **7 skipped** Android cases are the opt-in live-backend tests, which run only with
`-Dtsm.e2e.baseUrl`. Run against a real Flask server they were **7/7 passed, `skipped="0"`**. The 179 ML
tests are `ml/tests` (86) plus `ml/future_risk/tests` (93). Instrumented Android tests were never
executed — no device or emulator existed.

### 8.4 Model artifacts, figures and raw outputs

- **GeoLife:** `ml/outputs/clean/` (`trajectory_summary.csv` 18,670 rows, `points_clean_sample.csv`
  1,429,740 rows), `ml/outputs/eda/` (12 figures, 11 tables), `ml/outputs/train/`,
  `ml/outputs/eval/` (`eval_report.json`, `eval_metrics.csv`, `top_anomalies.csv`,
  `injection_scores.csv`), `ml/outputs/predict/`.
- **UCI HAR:** `ml/outputs/har/clean/`, `ml/outputs/har/eda/` (6 figures, 5 tables),
  `ml/outputs/har/train/` (reports, per-class, figures, model JSON),
  `ml/outputs/har/fall/` (ROC, confusion, signal figure, model JSON).
- **UniMiB / future-risk:** `ml/outputs/future_risk/` — `metrics.json`, `dataset_provenance.json`,
  `tuning.json`, `calibration_reliability.csv`, `test_predictions.csv` (10,415 rows),
  `future_risk_reference_cases.json`, `figures/` (6 plots).
- **Map / weather / history / transport:** `ml/outputs/osm/`, `ml/outputs/weather/`,
  `ml/outputs/history/`, `ml/outputs/transport/`.

---

*Every number in this report was read out of a file in `ml/outputs/`, `ml/models/` or
`app/src/main/assets/`. Where evidence does not exist, the text says so instead of supplying a number.
The full-length version of this report, including the complete figure galleries, the dataset-by-dataset
licence discussion and the full unavailable-metrics inventory, is preserved in
`docs/data_and_ml_analysis_full.md`.*
