# Offline water verification — plan

Status: **plan only, nothing built.** Written 2026-08-10.

Water verification is the last feature that genuinely needs a model and is worth
keeping. Flashcard drafting can fall back to ML Kit Text Recognition plus
heuristics; study chat is a real casualty of going key-free and stays cloud-only.
Water is different: it is the thing that gets a half-asleep person out of bed at
06:00, it is on the critical path of an alarm the user cannot dismiss, and it is
the one place where "no API key" today means dropping to a 30 s honor-mode hold
that anyone can lie through. Making it work offline is what makes the app's
central promise — *an alarm you can't cheat* — true without a key.

## What is already in our favour

The current design deliberately separates **observation** from **verdict**, and
that seam is the whole reason this plan is cheap.

`KimiRepository.verifyWater` asks the model only for observations:

| Field | Meaning |
|---|---|
| `sameContainer` | is this the same bottle in both shots |
| `levelReadable` | is the water line locatable in both shots |
| `beforeFill` / `afterFill` | how full, as a fraction 0..1 |
| `scaleMlReadable` + `beforeLevelMl` / `afterLevelMl` | printed ml marks, when legible |
| `refilled` | topped up between the shots |

Everything that *decides* anything is then recomputed locally, in plain Kotlin:
ml = fill drop × the user's declared `bottleCapacityMl`, floored to `ROUND_TO_ML`
(10 ml), accepted only when same bottle AND readable AND not refilled AND
≥ `MIN_CONSUMED_ML` (30 ml) AND (a legible scale drop OR ≥ `MIN_FILL_DROP` = 5 %
fill drop). The running tally, the chaining of each accepted "after" into the
next "before", the rejection count and the emergency dismiss all live in
`WaterVerifyViewModel`.

So going offline does **not** mean re-deriving the policy. It means replacing one
producer of six observations. Everything downstream — the tally, the tolerance,
the strike count, the logging — is untouched.

## Architecture: mirror the `MotivationSource` move

`OfflineMotivation` is the template. It implements `MotivationSource`,
`NetworkModule.provideMotivationSource` binds the offline one unconditionally,
and `KimiRepository.motivationalMessage` was deleted outright.

Do the same shape here:

```kotlin
interface WaterObserver {
    suspend fun observe(before: ByteArray, after: ByteArray): KimiResult<WaterObservation>
}
```

- `WaterObservation` = the six fields above, with no `consumedMl` / `accepted`
  (those are the verdict, not an observation — move that arithmetic into a pure
  `WaterVerdictRules.decide(observation, bottleCapacityMl)` that both
  implementations share and that is unit-testable without any image at all).
- `CloudWaterObserver` — today's `verifyWater` body, unchanged.
- `OnDeviceWaterObserver` — the new one.

Unlike motivation, do **not** delete the cloud path on day one. Bind on-device
when it is confident and fall back to cloud only if a key exists (see Phase 3).
The offline path handling only the confident cases is already a win: it removes
the key requirement for the common case and cuts latency at 06:00 to nothing.

## Free wins that need no model at all

Two of the six observations should never have been a model's job:

1. **`refilled`** is `afterFill > beforeFill + noise`. The level going *up* is
   the definition of a refill. Compute it from the two fill numbers, whoever
   produced them. (The model already gets this right most of the time, but it is
   arithmetic, not perception.)
2. **`scaleMlReadable` / the ml marks** are OCR — ML Kit Text Recognition,
   on-device, free, already a dependency family we ship. Numbers printed on a
   bottle next to the water line are exactly what it is good at. Even offline
   this can stay: crop a vertical strip around the detected line, read the
   nearest legible numeral above and below, interpolate.

That leaves the genuinely perceptual part: **where is the water line, and where
are the top and bottom of the bottle** — plus **is it the same bottle**.

## Phase 0 — Capture a dataset before writing any CV (do this first)

The hard part is not the algorithm, it is having 500+ real photo pairs from real
bathrooms at 06:00 with the curtains shut. Build the collector *now*, while the
cloud path still ships, and let ordinary use label itself:

- Debug/opt-in only. A Settings toggle under a "Help improve verification"
  heading, **off by default**, visible text saying photos of your bottle stay on
  the device and are only exported when you tap Export. No silent upload, ever —
  the privacy policy currently promises photos are not retained, and this must
  not quietly contradict it.
- On each `verifyWater` call, when the toggle is on, write to app-private
  storage: `before.jpg`, `after.jpg`, and the cloud verdict JSON.
- The cloud verdict is the **weak label**. This is distillation: the expensive
  model that already runs labels the training set for the cheap one for free.
  Spot-check by hand — the fill fractions are eyeballed by the model too, so
  treat them as ±5 %, not truth.
- Export via SAF, same as the existing CSV export.

Target before moving on: **300 pairs, ≥ 5 distinct bottles, ≥ 3 lighting
conditions** (daylight, warm indoor lamp, dim). Your own two devices plus the
closed-test group is a plausible source if any tester opts in.

## Phase 1 — Classic CV baseline (try to stop here)

A water line in a translucent bottle is a strong, near-horizontal discontinuity:
refraction darkens and compresses what is behind the water, so the region below
the line has different mean intensity, saturation and local contrast from the
region above it.

Baseline, in order:

1. Locate the bottle: largest vertical convex blob, or grab-cut seeded from the
   centre column. A framing overlay in the capture UI (a bottle-shaped guide) is
   worth more than any algorithm here — constrain the input instead of solving
   the general case.
2. Collapse the bottle crop to a **1-D column profile**: for each row, mean
   intensity + mean saturation across the bottle's width.
3. The water line is the row with the largest step in that profile, found by
   convolving with a step kernel. Confidence = step magnitude relative to the
   profile's noise floor. **This is what drives `levelReadable`** — a weak step
   means "can't see the line", which is exactly the honest answer.
4. `beforeFill` / `afterFill` = (bottleBottom − line) / (bottleBottom − bottleTop).
5. `sameContainer`: compare a cheap descriptor of the two crops — aspect ratio,
   dominant hue of the cap region, colour histogram correlation. Threshold it.
   This does not need to be clever; it needs to reject "a different bottle" and
   tolerate "same bottle, moved 10 cm and rotated".

Implement it in pure Kotlin over a downscaled greyscale+HSV array, no OpenCV
dependency (OpenCV's Android AAR is ~40 MB and the AAB is already 39 MB). Keep it
a pure function from pixel arrays to observation so it unit-tests against the
Phase 0 corpus with no device and no camera.

**Accept Phase 1 and ship it** if, on a held-out slice of the corpus:
- fill fraction within ±5 % of the cloud label on ≥ 80 % of pairs, and
- `levelReadable = false` on the pairs a human agrees are unreadable (recall on
  the reject class matters more than the regression error — a wrong *number* only
  miscredits some ml, a wrong *readable* silently accepts noise).

## Phase 2 — Small trained model (only if Phase 1 misses)

If the profile method is beaten by dark bottles, labels wrapped across the water
line, or busy backgrounds:

- Task: **keypoint regression**, not segmentation. Three scalars from a bottle
  crop — `topY`, `bottomY`, `lineY`, normalised to the crop — plus one confidence
  logit for "line visible".
- Backbone: MobileNetV3-Small or smaller at 224×224. This is a 3-number
  regression on a constrained subject; it does not want a big model. Expect
  < 3 MB quantised.
- Train off-device in PyTorch, export to LiteRT (`.tflite`), int8 quantised, run
  via the LiteRT Android runtime. New dep; measure the AAB delta before
  committing to it.
- Same-bottle check can stay classic (Phase 1 step 5) — don't spend a model on it.

## Phase 3 — Cutover

1. Bind `OnDeviceWaterObserver` as the default `WaterObserver`.
2. If it returns low confidence (`levelReadable = false`) **and** a key is
   configured, retry once against `CloudWaterObserver` before showing a
   rejection. Best of both, and it degrades to today's behaviour exactly.
3. With no key, low confidence is just a normal rejection with the existing
   "try better light and an angle where the level shows" message — and after
   three, the existing emergency dismiss. **The 30 s honor-mode hold for
   `KimiResult.NoKey` disappears**, which is the actual goal of this document.
4. Update `CLAUDE.md`, the privacy policy (photos now never leave the device on
   the default path — a strictly better claim), and the Play Data safety answers.

## Risks and non-goals

- **Opaque bottles are unsolvable.** Steel flasks have no visible line at any
  price. The honest answer is `levelReadable = false` forever, and Settings
  should say so: verification needs a translucent bottle.
- **This is not anti-adversarial.** Neither is the cloud version. Someone
  determined to pour water down the sink to beat their own alarm will succeed.
  The bar is "harder than pressing snooze", not "tamper-proof".
- **Don't regress the tally UX.** The chaining in `WaterVerifyViewModel` is what
  makes a single mouthful count; a noisier local estimator makes the tolerance
  (`TARGET_TOLERANCE_ML` = 50) matter more, not less. Revisit it with real
  numbers from Phase 1, don't guess.
- **Don't collect photos without an explicit, off-by-default opt-in.** Getting
  this wrong is a Play policy problem and a privacy-policy contradiction, not
  just a bad look.

## Rough effort

| Phase | Work |
|---|---|
| 0 — collector | half a day, then wall-clock waiting for pairs |
| 1 — CV baseline | 2–3 days including the test harness |
| 2 — trained model | a week, only if Phase 1 fails |
| 3 — cutover | half a day |

Phase 0 is the one that blocks everything and costs the least. Build it next time
the water path is open regardless of whether Phases 1–2 ever happen.
