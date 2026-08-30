# Offline water verification — prototype and corpus tools

Status: **ported to Kotlin and shipping as the default. Still never run against
a real photograph.**

This is Phase 1 of `docs/OFFLINE_WATER.md` — the classic-CV baseline that removes
the API-key requirement from water verification. It exists here in Python rather
than Kotlin because the develop-and-look-at-it loop is seconds instead of a
Gradle build. The algorithm has now been ported; **this directory stays as the
test harness**, and it is the only place the detector can be measured against a
corpus of photographs.

The port is held to this code by a cross-language fixture. `portable_synth.py`
renders bottles from an explicit LCG rather than numpy's RNG, so the identical
renderer exists in Kotlin (`PortableScene.kt`) and the same pixels can be pushed
through both implementations. Run:

```bash
$PY -m waterline.portable_synth      # prints the constants WaterLineDetectorTest asserts
```

whenever `detect.py` changes, and paste the output into `WaterLineDetectorTest`.
A deliberate tuning change makes those constants stale, not wrong — but leaving
them stale means the Kotlin side is no longer the same detector.

## Why this is the last thing standing between the app and no key

Motivational lines and the weekly report already moved on-device. Flashcards can
fall back to ML Kit Text Recognition. Study chat is a genuine casualty. Water is
the one that matters: it sits on the critical path of an alarm you cannot dismiss
at 06:00, and with no key it currently drops to a 30-second honour-mode hold that
anyone can lie through. Making it work offline is what makes *an alarm you can't
cheat* true without a key.

## What is here

| File | What it does |
|---|---|
| `waterline/detect.py` | The detector and the same-bottle check. Pure functions, no I/O. **This is the thing that was ported.** |
| `waterline/synth.py` | Synthetic bottle renderer with exact ground truth, for tests. |
| `waterline/portable_synth.py` | The same, minus library randomness, so Kotlin can render identical pixels. |
| `tests/` | 61 regression tests — detector, same-bottle check, breadth importer. `pytest tests/ -q` |
| `waterline/lines.py` | The breadth corpus: single images with a water-line label. |
| `evaluate.py` | Measures either corpus against its gate. Writes overlays. |
| `sequences.py` | Turns a shooting session into every labelled pair it contains. |
| `import_masks.py` | Derives line labels from a public dataset's liquid masks. |
| `label.py` | Click the water line on any pile of bottle photos. |

Run everything with the torch venv that already exists — no new environment:

```bash
PY=/home/ogenrwotdaniel/PycharmProjects/ilyasutskever/.venv/bin/python
cd tools/water
$PY -m pytest tests/ -q          # 42 tests, ~2 s
$PY evaluate.py --synthetic -v   # smoke run, needs no photographs
```

## The algorithm, and the plan's one wrong assumption

`docs/OFFLINE_WATER.md` proposed collapsing the bottle to a row profile and
taking the largest step. **That was measured and it is not good enough.**
Averaging across the bottle's width destroys the one cue that identifies a water
line. A blotchy background seen through a clear bottle produces steps in the row
profile *larger* than the water line's own — measured at 0.13 vs 0.10 luminance
on a case the detector then got confidently wrong. Real bathrooms are full of
these: a tiled wall meeting paint, a countertop edge, a shadow gradient.

What separates them is geometry, not photometry:

> **A water line is horizontal across the whole bottle, because gravity makes it
> so. A background boundary crossing the bottle is not.**

So the primary detector asks, for each row, whether a *coherent* horizontal edge
exists there — the vertical gradient agreeing in sign across every column,
expressed as an across-column t-statistic. Switching to it cut fill error **11×**
on the synthetic corpus (MAE 0.043 → 0.0039).

Three cues, each covering the others' failure:

1. **Coherence** — is there a horizontal edge here (the gravity cue).
2. **Band suppression** — an opaque label is a *band*: two coherent edges of
   opposite sign, a short distance apart. Water is a *half-space* boundary: one
   edge, with water continuing to the bottle's bottom. Edges with an
   opposite-signed partner above *or below* get damped. Searching both
   directions is load-bearing — downward-only missed a label's bottom edge, the
   single worst failure mode, and fixing it took labels from 80% → 90%.
3. **Global two-region split** — kept as a multiplicative gate. Fooled by
   background structure on its own; as a gate it vetoes coherent edges that
   don't also separate the column into air-above and water-below.

Confidence is the coherence t-statistic itself, so a weak peak means *I cannot
see the line* — which is the honest answer for an opaque steel flask, and exactly
what `levelReadable = false` is supposed to carry.

### The same-bottle check, and the trap in it

The plan's step 5 said "compare a cheap descriptor of the two crops — aspect
ratio, dominant hue of the cap region, colour histogram correlation". Comparing
the two crops is exactly what does not work, for a reason that only shows up
once you try it:

> **Drinking converts a band of the image from water to air.** So an honest
> before/after pair of the SAME bottle scores as two different bottles, and the
> harder you drink the worse it scores — the check fails precisely on the pairs
> it is supposed to pass.

The fix is to compare only what did *not* change. The rows above the higher water
line are air in both photos; the rows below the lower line are water in both.
Those two bands are directly comparable, and everything that changed lies between
them (`shared_bands`).

Two cues over those bands, weighted evenly because they fail differently:

1. **Colour**, as a **chromaticity** histogram — `r/(r+g+b)`, `g/(r+g+b)`. Raw
   RGB bins were tried first and failed the case that matters: scaling every
   channel is exactly what switching a lamp on does. Measured, a 40 % brightness
   drop between the two shots scored the same bottle at **0.53** against a 0.55
   floor — a rejection, at 06:00, for turning a light off. Chromaticity is
   invariant to it by construction and the same case then scored **0.98**.
2. **Wall profile** — per-column mean luminance, compared by Pearson correlation.
   The curved walls throw specular highlights down both edges, so this is a cheap
   stand-in for the silhouette without segmenting anything.

Measured on synthetic pairs (n=40 each): same-bottle ≥ 0.99, swapped ≤ 0.63. The
floor sits at **0.55**, low in that gap on purpose — a false "different bottle"
is a rejection the user sees, while a missed swap earns the cheat nothing, since
only *drops* are ever credited.

`evaluate.py --swap-negatives N` manufactures the negative class for free by
crossing the corpus over itself: the BEFORE of one bottle against the AFTER of
another. Without it the check can only ever be measured on false rejections,
which flatters any threshold low enough to accept everything.

### Measured on synthetic scenes only

| Class | n | fill MAE | within ±5% | within ±2% |
|---|---|---|---|---|
| clear bottles, incl. dim + tinted | 40 | 0.0094 | 100% | 100% |
| printed label on the bottle | 10 | 0.0542 | 90% | 90% |
| opaque flask (must be rejected) | 10 | — | 0/10 readable ✅ | |

Pair-level: ml error MAE 7.9 ml, p90 16.6 ml — inside the app's 30 ml minimum
and 50 ml tolerance.

**These numbers are not evidence.** A synthetic image contains exactly the cues
whoever wrote the generator thought to put in, so it tests "did I break the
algorithm", not "does the algorithm work". The gate must be met on photographs.

## Where the data comes from: breadth vs depth

Different users own wildly different bottles, so the corpus has to answer two
questions that need **different data and different labels**. Conflating them is
what makes the data problem look bigger than it is.

| | **Breadth** | **Depth** |
|---|---|---|
| Question | Is the line found on a bottle shape I've never seen? | Does a measured 120 ml pour read as 120 ml? |
| Needs | one image, one number (the line's y) | a *pair* + a measured pour |
| Label cost | one click, ~2 s — or free from a liquid mask | a jug and a sink |
| Source | **public datasets, web images**, your camera roll | only you |
| How many bottles | as many as possible | a handful |
| Run it | `evaluate.py --lines lines.csv` | `evaluate.py --corpus corpus/` |

Breadth is where internet data genuinely helps, and it is the half that worries
about bottle diversity. Depth cannot come from the internet at all — no public
dataset contains a before/after pair with a measured pour, and none ever will —
but it only needs a few bottles, because it validates *arithmetic and tolerance*,
not perception across shapes.

Because Phase 1 does no training, **no borrowed image ever ships**. Nothing is
derived from them but ~6 threshold numbers, which keeps the licensing clean. That
changes if Phase 2 trains a network on scraped or CC-BY data — attribution and
redistribution terms become real constraints then.

### What is actually available publicly (searched 2026-08-23)

The honest summary: **plenty of data, mostly the wrong label shape.** Nearly
everything public labels fill as 3–5 coarse buckets — `Full/Half/Overflowing`, or
`empty/little/half/much/full`. Buckets 20% wide cannot validate a ±5% tolerance,
and the app has to resolve a ≥5% fill drop. Industrial sets (saline/IV bottles,
amber glass) are identical containers under controlled factory light, the opposite
of the distribution that matters.

| Source | Size | Labels | Verdict |
|---|---|---|---|
| [liquidfy/liquicontain](https://universe.roboflow.com/liquidfy/liquicontain) (from [LiquidMonitorAI](https://github.com/IsiPaz/LiquidMonitorAI)) | ~2.4k | **semantic segmentation incl. a `liquid` class** | **Pull this first.** A liquid mask's top edge *is* the line → `import_masks.py` derives labels automatically. Needs a free Roboflow key. |
| [bottle-fill-level](https://universe.roboflow.com/bottle-fill-level/bottle-fill-level) | ~290 | fill level | Small but on-topic; same importer if exported as segmentation. |
| [LCDTC](https://pmc.ncbi.nlm.nih.gov/articles/PMC10422399/) | 5,916 imgs / 10,453 containers | boxes on **containers only**; liquid as 5 classes | Best *capture* distribution (~4k cellphone shots from kitchens, restrooms, living rooms) but wrong labels and **not downloadable** — author request only. CC BY 4.0. |
| [water-bottle-image-classification](https://github.com/emirhansilsupur/water-bottle-image-classification) | — | `Full/Half/Overflowing` | Too coarse to be useful. |
| [IV/saline fill-level dataset](https://www.sciencedirect.com/science/article/pii/S2352340920313548) | — | 4 discrete levels | Industrial, identical bottles, controlled light. |
| [TransProteus](https://arxiv.org/pdf/2109.07577) | CGI | 3D shapes + liquid masks | Synthetic; lab vessels. Supplement at best. |

So: derive what you can from `liquidfy/liquicontain`'s masks, then click-label
anything else — including plain bottle photos from any source, which is what
`label.py` is for. Diversity is the point; a hundred clicked images across fifty
bottle shapes is worth more than a thousand of one bottle.

```bash
# 1. Breadth, free: derive labels from an existing dataset's liquid masks
$PY import_masks.py path/to/_annotations.coco.json --out corpus/breadth/lines.csv
$PY evaluate.py --lines corpus/breadth/lines.csv --images path/to/images -v \
     --overlays /tmp/ov      # ALWAYS eyeball these: a borrowed label can be wrong

# 2. Breadth, cheap: click the line on any other bottle photos
$PY label.py ~/Pictures/bottles --out corpus/breadth/lines.csv

# 3. Depth: your own measured pours (below)
```

> Spot-check the overlays every time. A mask-derived label is only as good as the
> annotator's polygon, and the overlay draws truth in yellow against the detection
> — the one view that separates "detector is wrong" from "label is wrong".

## Shoot the depth corpus

`docs/OFFLINE_WATER.md` asks for 300+ pairs and 5+ bottles, labelled by the cloud
model as a weak label. Breadth now covers the bottle-diversity half, so this
shrinks a lot — and two further improvements make what's left an evening's work:

**1. Use a measuring jug, not the cloud model.** The doc warns to treat the
model's fill fractions as ±5%. You cannot measure whether you are within 5% of
the truth using a label that is itself only within 5% of the truth. A kitchen jug
costs nothing and gives exact truth.

**2. Shoot sequences, not pairs.** Photograph one bottle repeatedly as you pour
measured amounts out of it. Every ordered pair *within* that sequence is a valid
labelled pair, with truth = the difference in cumulative pour. `n` shots give
`n(n-1)/2` pairs, not `n/2` — 8 shots give 28 pairs.

### Shoot it from inside the app (easiest path)

The debug build has a capture tool that enforces this protocol and writes the
`shots.csv` below for you, so the bookkeeping cannot drift from the images —
which is the part that actually goes wrong. Home -> Developer testing ->
**Water corpus capture** (debug builds only; it retains photographs and the
shipping app deliberately does not).

Set the sequence, bottle, capacity and lighting once, then for each shot: update
the cumulative millilitres off the jug, tap **Record shot**. The screen shows how
many labelled pairs the sequence has produced so far (`n(n-1)/2`).

```bash
adb pull /sdcard/Android/data/com.ogenrwotdaniel.wakeproof/files/water-corpus \
         tools/water/corpus
cd tools/water
$PY sequences.py corpus/shots.csv > corpus/manifest.csv
$PY evaluate.py --corpus corpus/ --gate -v --overlays /tmp/ov
```

It shoots rear-lens only and never offers a flip: the two cameras differ in field
of view, colour rendering and resolution, and `same_container` compares exactly
those properties across a pair.

Or do it by hand with any camera, following the protocol below.

### Protocol

Target: **3 translucent bottles × 3 lightings × ~8 shots ≈ 72 photographs**,
which `sequences.py` expands to 200+ labelled pairs. Fewer bottles than the plan
originally asked for, because bottle diversity is now the breadth corpus's job —
this corpus exists to check the millilitre arithmetic and the 50 ml tolerance.

1. Pick a bottle and note its true full capacity in ml.
2. Fill it to the brim. Stand it somewhere with a **normal, cluttered
   background** — a plain wall makes the corpus easier than reality, which is
   worse than useless.
3. Shoot it upright, filling most of the frame, camera roughly level with the
   middle of the bottle. Keep the framing **consistent within a sequence**.
4. Pour a measured amount into the jug (80–150 ml is realistic for a gulp).
   Record the *cumulative* total poured. Shoot again. Repeat ~8 times.
5. Repeat the sequence under: **daylight**, **warm indoor lamp**, and **dim**
   (curtains shut, one lamp — the actual 06:00 case).
6. Deliberately include, as separate sequences:
   - one **opaque steel flask** (mark `unreadable=1`) — the reject class needs
     real examples or `CONFIDENCE_FLOOR` is fitted to a synthetic guess;
   - one bottle with a **large printed label** across the middle;
   - one **refill** sequence where the total goes *down*, to check `refilled`.

Record one row per photograph as you go:

```csv
sequence,bottle,capacity_ml,lighting,filename,poured_total_ml
s1,clear-750,750,daylight,IMG_2001.jpg,0
s1,clear-750,750,daylight,IMG_2002.jpg,100
s1,clear-750,750,daylight,IMG_2003.jpg,220
```

Then:

```bash
mkdir -p corpus/images && cp /path/to/photos/*.jpg corpus/images/
$PY sequences.py shots.csv -o corpus/manifest.csv
$PY evaluate.py --corpus corpus/ -v --overlays /tmp/overlays --sweep
```

`--overlays` is the important one. Looking at the annotated picture is how you
find out the bottle was sideways, the label was in the way, or the framing guide
was ignored — none of which show up in a MAE.

> Photographs are **not** committed. `corpus/` is gitignored: the privacy policy
> promises bottle photos are not retained, and a corpus in the repo would
> contradict it. Keep them locally, or on your own drive.

## The acceptance gate

From `docs/OFFLINE_WATER.md`, checked automatically by `evaluate.py --gate`:

- fill fraction within **±5%** of truth on **≥80%** of pairs, **and**
- `readable = false` on every pair a human agrees is unreadable.

Reject-class recall matters more than regression error. A wrong *number* only
miscredits some ml against a target that has a 50 ml tolerance and keeps asking
for more photos. A wrong *readable* silently accepts noise as a drink — the
app's central promise failing quietly.

## The Kotlin port (done — 2026-08-30)

Everything in `detect.py` is means, variances, prefix sums and separable box
blurs, deliberately — no OpenCV (its Android AAR is ~40 MB and the release AAB is
already 38 MB), no LiteRT, no AAB growth. The port added exactly one dependency,
`androidx.exifinterface` (tens of KB), and only because orientation matters.

It shipped ahead of the gate, with the Settings switch "Read the water level on
this device" as the way back to the cloud path if a real bottle is misread. That
is a deliberate trade: the gate needs a corpus nobody has shot yet, and the thing
it was blocking — water verification that needs no API key — is the whole point.

| Python | Kotlin |
|---|---|
| `detect.py` | `verify/water/WaterLineDetector.kt` |
| `same_container` and friends | `verify/water/ContainerMatch.kt` |
| the verdict rules | `verify/water/WaterVerdictRules.kt` |
| — | `verify/water/WaterImageSampler.kt` (decode, EXIF-rotate, resample) |
| — | `verify/water/WaterObserver.kt` (`LocalFirstWaterObserver`) |

Two things the port had to add that this harness does not need:

- **Orientation.** `evaluate.py` applies EXIF transpose on load; the app has to
  do it too, and `BitmapFactory` does not. The whole method rests on the line
  being horizontal in the pixel grid, so a sideways photo defeats everything.
- **Scale.** `GRADIENT_SMOOTH_ROWS` and the band spans are row counts fitted at
  ~360 rows. A 12 MP capture is ~3000 rows, which would quietly make the
  smoothing a tenth of what it is meant to be. The sampler resamples to
  `WaterLineDetector.ANALYSIS_HEIGHT`.

The binding is **on-device first, cloud as a second opinion**: an acceptance is
answered on the device with no network at all, and any non-acceptance asks the
model first when a key exists — so every user-visible rejection gets the stronger
opinion. The `KimiResult.NoKey` honour-mode hold is gone.

One thing this prototype does **not** do, and one it now does:

- **Printed ml marks** (`scaleMlReadable`) — still not implemented. It is ML Kit
  Text Recognition's job: on-device, free, and Android-only, so there is nothing
  to prototype here. `compare()` hardcodes `false`, which is safe — the app falls
  back to the fill fraction when marks aren't legible.
- **`sameContainer`** — implemented, and not the way the plan assumed. See below.

## Known limitations

- **Opaque bottles are unsolvable.** A steel flask has no visible line at any
  price. `readable = false` forever is the honest answer, and Settings should say
  verification needs a translucent bottle.
- **A real line within `BAND_MAX_SPAN` of a label edge gets damped**, because
  band suppression cannot tell it from the label's other edge. Damped, not
  deleted (`BAND_DAMPING = 0.25`), so a strong line still wins. Pinned by
  `test_suppress_bands_damps_any_edge_near_an_opposite_one`. First thing to
  re-examine if labelled bottles underperform on the real corpus.
- **A line within `MIN_SIDE_FRACTION` of the crop's top or bottom cannot be
  found** — a brim-full or nearly-empty bottle. Currently 3% of crop height.
- **Sideways photos break everything.** A rotated bottle makes the water line
  vertical and the whole method is built on it being horizontal. `evaluate.py`
  applies EXIF transpose; the app must confirm orientation before profiling.
- **Not anti-adversarial**, and neither is the cloud version. Someone determined
  to pour water down the sink to beat their own alarm will succeed. The bar is
  "harder than pressing snooze".
- Every tunable in `detect.py` was fitted on synthetic data. They are starting
  points, not measurements.

## If the gate fails: Phase 2

Only then. Per the plan: keypoint regression (three scalars — `topY`, `bottomY`,
`lineY` — plus a visibility logit), MobileNetV3-Small at 224², int8-quantised to
LiteRT, expected < 3 MB. Train on CPU here — there is no CUDA on this machine
(16 cores, 14 GB), which is fine for a 3-number regression on a constrained
subject but rules out anything larger. The recipes are in the Géron notebooks at
`~/PycharmProjects/ilyasutskever`: `12_deep_computer_vision_with_cnns.ipynb` and
`Appendix_B_mixed_precision_and_quantization.ipynb`. Measure the AAB delta before
committing to the LiteRT runtime dependency.

The corpus you shoot for Phase 1 is the same corpus Phase 2 trains on, so none of
that shooting is wasted either way.
