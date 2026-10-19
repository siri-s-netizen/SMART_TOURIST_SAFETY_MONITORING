# The simple version

### What we built, what it does, and what it honestly can't do

*You don't need any technical background to read this. If you do want the full detail — every file,
every number, every caveat — it's in [`docs/data_and_ml_analysis.md`](data_and_ml_analysis.md).*

---

## If you only read one thing

- We built a **phone app for tourists**. It watches how the phone moves and shows three simple things:
  **what the person is doing**, **whether the phone just felt a hard bump**, and **whether they've
  walked into a no-go area**.
- **Five things in it were taught by showing it examples.** Two things are just **rules a person
  wrote**. We keep those apart on purpose.
- It works well **on the data we had**. But that data came from volunteers and from laboratories —
  **never from a real tourist**.
- The best result: it can warn about a **recorded lab fall about one second before it lands**, and it
  catches **93 out of 100**.
- The honest problem: during ordinary activity it **also cries wolf about 5 times a minute**. So it is
  only ever a hint on the screen. **It never calls for help.**

---

## 1. What the app actually does

Imagine a tourist opens the app before a trek.

The phone starts watching two things: **where it is** (GPS) and **how it's moving** (the motion
sensors). From that, the app shows a few cards on the screen:

- *"You appear to be walking."*
- *"The phone felt a hard landing."*
- *"You are 40 metres from the restricted area."*
- *"Something hard might land in the next second."*

The tourist also gets a big **SOS button**, and a list of emergency contacts.

An officer, meanwhile, opens a website on their computer. They see a list of tourists, where each one
last was, and any SOS alerts. They also see a **separate panel** with the fall warnings — kept apart
from emergencies, so a guess is never mistaken for a cry for help.

**Two important things:**

1. **The thinking happens on the phone.** It does not need the internet to work out what you're doing.
2. **Only short summaries are sent.** Never a live feed of your movement, and never raw sensor data.

---

## 2. Seven things the app knows — and which ones are really "AI"

This is the part people most often get wrong, so here it is plainly.

| What it shows | In plain words | Taught by examples? |
|---|---|---|
| **Activity** | "Walking? Stairs? Sitting? Standing? Lying down?" | **Yes** |
| **Hard-landing detector** | "Did the phone feel a hard bump, then go quiet?" | **Yes** |
| **Next-second fall hint** | "Will a hard impact land in the next second?" | **Yes** |
| **Weird-GPS score** | "That bit of movement looks odd" | **Yes** — but with no answer key |
| **Transport mode** | "Bus, bike, car, walking, subway?" | **Yes** |
| **Geofence** | "Are they inside the no-go area?" | **No — just a line on a map** |
| **"Approach risk"** | "How concerning is this approach?" | **No — a scorecard a person set up** |

The last two matter. The **geofence is a line an officer draws on a map**. It has no intelligence in
it at all — it just answers inside or outside. And the **"approach risk" number is a scorecard**: it
adds up fixed points for how close you are, what time it is, and how often you've been near that spot
before. A human chose those points. Nothing learned them.

Neither of those two has a probability, and neither is a prediction. The reports say so every time the
number appears.

---

## 3. Where the data came from

We didn't invent any of this. Five real sources were downloaded and read. Here's what each one
actually is, in everyday terms.

**GeoLife — a diary of GPS pings.**
Microsoft collected GPS traces from **182 volunteers over about twelve years, mostly in Beijing**.
Think of it as "where my phone was" recorded every few seconds, across **18,670 trips** — roughly
**1,021,454 km** of travel.
*Good for:* what real movement looks like, and how often people cross a boundary.
*Can't do:* it contains **no dangers, no injuries, no incidents**. So it is impossible to teach a
computer "risk" from it.

**UCI HAR — thirty people doing exercises in a lab.**
**30 volunteers** wore a phone on the waist and did six things: walk, walk upstairs, walk downstairs,
sit, stand, lie down.
*Good for:* teaching the app those six activities, because here real people labelled what they were
doing.
*Can't do:* **there are no falls in it.** Nobody fell. Not once.

**UniMiB SHAR — thirty people falling on purpose.**
**30 volunteers in a lab deliberately fell**, eight different ways, and also did nine normal daily
activities. These are **real falls** — just in a laboratory.
*Good for:* the "next-second fall" hint, because it is the only data we had where a fall really
happened.
*Can't do:* everyone is a healthy adult in a controlled room. A lab fall is not a tourist accident.

**OpenStreetMap — the map itself.**
Free, open map data. We pulled a 1.5 km × 1.5 km patch of central Beijing and found **15,980 map
items** — buildings, footpaths, steps, cafés, toilets. It is used to **draw the map**. **No model uses
it.** (OpenStreetMap asks for a credit line, so the app and the reports carry one.)

**Weather — and a small honesty note.**
The brief said to use OpenWeatherMap. That service needs a paid key, and we didn't have one. So we used
a free one called **Open-Meteo** instead — and every single file says which one it actually used. It
was never quietly passed off as OpenWeatherMap. We got **574 days** of weather for two places: the
middle of the GeoLife area, and a mountain trekking spot called Huangshan. Huangshan turned out much
wetter — **2,676 mm of rain against 806 mm**, and more harsh days (38% against 30%).

There is also a tiny **fake dataset**, used only to check the machinery works when the big files aren't
installed. Anything made from it is stamped with a loud warning, and **not one number in these reports
comes from it.**

---

## 4. What we found — the good news

Every number below came out of a file that a training run wrote down. None of it is estimated.

**It can tell what you're doing — about 5 times out of 6.**
The version small enough to run on the phone gets the activity right **about 83 times out of 100**.
A much bigger version, too heavy for a phone, gets **93 out of 100**. We chose the smaller one, and we
report the cost honestly: about **9 points of accuracy** given up so it can fit in your pocket.

**It can see a lab fall coming — about one second early.**
This is the best result in the project. On people the model had **never seen before**, it flagged
**264 out of 284 recorded falls** at least once. That's **93 out of 100**. It even works when the
warning has the full second to run, not just at the last instant.

**It can spot a broken GPS trace — about 89 times out of 100.**
We couldn't test "danger" because there's no danger in the data. So we tested something we *could*
measure: we deliberately broke clean GPS traces (jumping a point two kilometres in one step) and asked
whether it noticed. It did, **89 times out of 100**. Then we ran the same test with the answers shuffled
— and it scored a coin flip. That's exactly what you want, because it proves the first number was real.

**It can guess how you're travelling — about 85 times out of 100.**
Bus, bike, car, walking, subway and so on. Useful context. **Not** a safety feature, and the reports
label it that way.

**Everything passes its tests.**
548 phone-app tests, 265 server tests, 41 end-to-end server checks, and 179 machine-learning tests —
**all passing**.

---

## 5. What we honestly can't do

A report with only the good news would be misleading. This is the important half.

### 5.1 We had no real falls, so we made some up
This is the single most important thing to understand.

The dataset used for the bump detector, **UCI HAR, has no falls in it at all.** So to teach the app
what a fall feels like, we **took honest recordings and edited falls into them** — a quick drop, a hard
spike, then stillness.

The detector spots those **planted** falls almost every time. **That does not mean it spots a real
person falling.** It means it spots the fall we planted.

Real falls are messier. Some are gentle. Many look like somebody sitting down quickly. The reports say
this in bold next to the score, and they also name the public datasets that *do* contain real falls —
because retraining on one of those is the one change that would make this a real fall detector.
**Nobody has done that yet.**

### 5.2 The false alarms are the real problem
The next-second fall hint is the one trained on real falls — and it has an honest flaw.

During perfectly ordinary activity, it raises a false "something hard is coming" roughly **5 times
every minute**.

Some movements set it off far more than others:

- **Jumping — 16 times out of 100**
- **Sitting down — 16 times out of 100**
- **Lying down — 13 times out of 100**
- Walking — 1 time out of 100
- Running — 2 times out of 100

And that makes physical sense. Jumping, sitting down and lying down are exactly the moments when a body
genuinely **drops under gravity** — so of course they can look like the start of a fall.

**This is why it can only ever be a hint.** A high warning raises no alarm, changes nothing, and never
calls for help. That isn't just a note in the manual — it's built into the code, and a test fails if a
warning ever causes an emergency to be recorded.

### 5.3 A dropped phone and a falling person look the same
Both are "a hard bump, then stillness". We found no data anywhere with somebody deliberately dropping a
phone, so this was **never tested**. We list it as an unknown instead of pretending otherwise.

### 5.4 A lab is not the real world
Every recorded fall here is an adult in a laboratory. Every activity recording is somebody doing
scripted movements with a phone on the waist or in a pocket.

**Nothing** was recorded from a tourist — not outdoors, not in the rain, not at altitude, not with a
backpack, not on a cable car. Public and lab data **do not prove anything** about real tourist safety.

### 5.5 It never ran on a real phone
There was no phone and no emulator available. So the sensors, the screens, the permission prompts, the
real GPS and the phone's own internet connection were **never tried on a device**.

The maths was checked against the training tools, and the screens were tested as code — but nobody ever
looked at them running on a phone.

### 5.6 Nobody ever used it
**No user study. No interviews. No survey. No usability test.** None, at any point.

So anything that sounds like "how it would feel to use" comes from tests, not from people.

### 5.7 Some numbers honestly don't exist
Where we couldn't measure something, the reports say **"not available"** and explain why, instead of
inventing a number.

The clearest example: the weird-GPS score has **no accuracy and no real-world detection rate**, because
GeoLife contains no answer key. Any such number would have been made up.

---

## 6. What happens when something goes wrong

This is the part that reassures people, and it's all tested.

**If there's no signal** — nothing is lost. The app writes the event into a small database on the
phone and sends it when the connection comes back.

**What gets sent** — a short summary, never a stream. The app never uploads raw sensor readings and
never uploads a continuous movement feed.

**When a fall hint changes** — a brief note goes to the officer. That's it.

**The officer's screen** — shows tourists, last known positions, and SOS alerts. Fall predictions sit
in a **separate panel**. They are never mixed into the emergency queue, and they are counted
separately.

**SOS is always the tourist's own choice.** The model never triggers it. There is a test that fails if
it ever does.

---

## 7. Why we keep the rules and the AI apart

There are four things in this project that a careless reader could blur into one vague idea of "the
app predicts danger". They are kept separate in the code, on the screen, and on the server.

1. **Activity** — what is this person doing? A guess, with a confidence. **Not** a safety score.
2. **Movement and falls** — was there an impact? Will there be one in the next second? A movement
   question. **Not** proof that anything happened.
3. **Geofence** — inside or outside a line. A **rule**. No learning, no probability.
4. **Approach risk** — a **scorecard** with fixed points. No learning, no probability.

The app shows all four, and each one says what it is. A test fails if the trained model's card ever
stops saying that it is *not* the other three.

---

## 8. Words you might see, explained

| Word | What it really means |
|---|---|
| **Unsupervised** | The model had no answer key. It can only say "this looks unusual". |
| **Anomaly score** | A "weirdness" number. Higher means stranger — **not** more dangerous. |
| **Precision** | Of the things we flagged, how many were actually real. |
| **Recall** | Of the real events, how many we managed to catch. |
| **False alarm** | We flagged something, but nothing was happening. |
| **Calibration** | When the model says "70%", is it right 70% of the time? |
| **Unseen people** | We tested on people the model had never met — like testing a doctor only on new patients. |
| **"Not available"** | We couldn't measure it, so we didn't guess. |

---

## 9. What we can and cannot claim

**We can say:**

- It recognises six everyday activities from a phone, on people it has never seen, about **83% of the
  time**.
- It can flag a **laboratory-recorded** fall up to one second early in **93 of 100** cases.
- It never raises an emergency by itself, never sends raw sensor data, and works with no signal.
- The geofencing, the SOS flow, the officer dashboard and the offline queue all work and are tested.
- Every number in the technical report can be traced to a file a script wrote.

**We cannot say:**

- That it detects real falls in the real world.
- That it predicts danger, injury, crime or medical events. No data for that exists here.
- That it tells a dropped phone apart from a falling person.
- That it has ever run on a phone, or been used by a tourist.
- That any tourist's safety has measurably improved.

---

## 10. The one-sentence version

> It's a well-built, honestly-documented demonstration that can recognise ordinary activities and
> **laboratory** falls from a phone — and it deliberately refuses to promise anything about real
> tourists, because nobody has ever tested it on one.

---

*Every number here also appears in the technical report, attached to the file it came from. Where
evidence doesn't exist, this document says so instead of supplying a number — and that is the most
important habit in the whole project.*

*Full technical detail: [`docs/data_and_ml_analysis.md`](data_and_ml_analysis.md)*
