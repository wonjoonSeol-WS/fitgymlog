# Publishing on Google Play

Everything to paste into Play Console when FitGymLog goes on Google Play. Until then, releases stay on GitHub.

## Before the first upload

1. **Developer account.** Create one at play.google.com/console ($25 once, ID check that can take a few days). A
   personal account made after November 2023 must run a closed test with at least 12 testers opted in for 14 days in a
   row before it can publish to production; an organisation account (D-U-N-S number) skips that.
2. **App signing.** Choose Play App Signing. The key in `~/.android/fitgymlog/` becomes the upload key; Google signs
   what users install. Then add the app signing key's SHA-1 (Test and release > App integrity) to the Android OAuth
   client in Google Cloud Console next to the upload key's, or Google Drive sync fails for installs from Play.
3. **Build the bundles.** Both share the package `io.fitgymlog.app` and the version in `gradle.properties`:

   ```sh
   ./gradlew :app:bundleRelease :wear:bundleRelease
   # app/build/outputs/bundle/release/app-release.aab   phone
   # wear/build/outputs/bundle/release/wear-release.aab  watch
   ```

4. **Add the Wear OS form factor** (Test and release > Advanced settings > Form factors), then upload the watch bundle to
   the Wear OS track of the same app. Watch users then install FitGymLog on the watch with one tap, without adb.

## Store listing

**App name:** FitGymLog

**Short description** (80 characters at most)

- EN: Gym log you run from your wrist, on a Galaxy Fit band or Wear OS watch. No ads.
- KO: 손목으로 기록하는 운동 앱. 갤럭시 핏 밴드나 Wear OS 워치로. 무료, 광고 없음.

**Full description (EN)**

> FitGymLog is a workout log for the gym that you can run from your wrist.
>
> On a Galaxy Fit band, the band's music buttons complete your set, move to the next exercise and take a rep off, and
> the band buzzes when rest is over. On a Galaxy Watch, Pixel Watch or other Wear OS watch, FitGymLog's watch app shows
> each exercise with one tap to log the next set, dials for weight and reps on the crown, and a full-screen rest timer.
>
> - Plate-by-plate weight entry and a reps number pad
> - Ready-made programs: StrongLifts 5x5, full body, upper/lower, push/pull/legs, or your own routines
> - Cardio in minutes, with treadmill speed, incline and distance
> - Form guide videos that play right in the app
> - Stats: weekly workouts, a weight trend, body composition and records for every exercise
> - A weekly check-in, and an optional coach on your own AI provider key or a local model
> - Health Connect (Samsung Health), optional Google Drive sync, Hevy and Strong CSV import and export
> - English and Korean
>
> Workouts stay on your phone. No account, no ads, no analytics. Free and open source:
> https://github.com/wonjoonSeol/fitgymlog

**Full description (KO)**

> FitGymLog는 손목으로 조작하는 헬스 운동 기록 앱이에요.
>
> 갤럭시 핏 밴드에서는 음악 버튼으로 세트를 끝내고, 다음 운동으로 넘어가고, 횟수를 하나 빼요. 휴식이 끝나면 밴드가 울려요.
> 갤럭시 워치, 픽셀 워치 등 Wear OS 워치에서는 FitGymLog 워치 앱으로 운동마다 한 번 눌러 세트를 기록하고, 크라운으로
> 무게와 횟수를 맞추고, 휴식 타이머를 화면 가득 볼 수 있어요.
>
> - 원판을 눌러 무게를 맞추고, 숫자 패드로 횟수 입력
> - 스트롱리프트 5x5, 전신, 상하체 분할, 밀기/당기기/하체 프로그램과 나만의 루틴
> - 분 단위 유산소, 러닝머신 속도·경사·거리
> - 앱 안에서 바로 재생되는 자세 가이드 영상
> - 통계: 주별 운동, 체중 추세, 체성분, 운동별 최고 기록
> - 주간 체크인과, 내 AI 키나 로컬 모델로 쓰는 선택형 코치
> - Health Connect(삼성 헬스), 선택형 Google Drive 동기화, Hevy·Strong CSV 가져오기와 내보내기
>
> 기록은 휴대폰에만 있어요. 계정, 광고, 분석이 없어요. 무료 오픈소스:
> https://github.com/wonjoonSeol/fitgymlog

**Graphics:** a 512 x 512 icon, a 1024 x 500 feature graphic, at least two phone screenshots (the site's
`images/*-en.webp` work) and at least one Wear OS screenshot (`docs/images/watch-*.png`, 454 x 454).

**Category:** Health & Fitness. **Contact:** the GitHub issues page. **Privacy policy:**
https://wonjoonseol.github.io/fitgymlog/privacy.html

## App content

- **Ads:** none. **App access:** everything works without signing in.
- **Target audience:** 18 and over (a health app with an AI coach; keeps it out of the Families policy).
- **Content rating:** fill in the questionnaire honestly; nothing in the app triggers a rating above Everyone.
- **Health apps declaration:** fitness tracking; not a medical device.

### Data safety

Nothing is collected by the developer. Data that leaves the phone goes only where the user sends it:

| Data type | Leaves the phone? | Where, and why | Optional |
|---|---|---|---|
| Health and fitness: exercise, workouts | Yes, shared | To the AI provider the user connects (coach); to the user's own Google Drive (sync); to Health Connect | Yes |
| Health and fitness: weight, body measurements | Yes, shared | To the AI provider the user connects, when the coach is on | Yes |
| Photos (scale or InBody screenshots) | Yes, shared | To the AI provider, only when the user picks one to read | Yes |
| App activity: messages to the coach | Yes, shared | To the AI provider the user connects | Yes |

Answer: data is encrypted in transit (https; plain http only to a model on the user's own network), users can delete it
(uninstall, and Drive's app management), and no data is collected for the developer. The watch link sends the workout
only between the user's own phone and watch.

### Permission declarations

**Health Connect** (declaration form, one reason per permission):

| Permission | Why |
|---|---|
| `WRITE_EXERCISE` | Saves each finished workout as an exercise session, with cardio as its own session |
| `WRITE_SPEED`, `WRITE_DISTANCE` | Saves the treadmill speed and the distance of cardio sessions |
| `READ_EXERCISE` | Skips cardio another app already recorded at the same time, such as a run the band detected; nothing read is stored |
| `READ_WEIGHT`, `READ_BODY_FAT` | Shows the weight trend and body composition in Stats, and works out the calorie target |
| `READ_HEALTH_DATA_HISTORY` | Shows the weight and body-fat trend over the past year rather than only the last 30 days |

**Foreground service type `mediaPlayback`:** "During a workout, FitGymLog keeps a media session playing so a Galaxy Fit
band's music controls can complete sets and show the current set; no audio plays. The user starts it by starting a
workout, it shows a notification with the set, and it stops when the workout ends." Play asks for a short video: start a
workout, show the set in the media notification and lock screen, press pause to complete a set.

**Exact alarms:** the app asks for `SCHEDULE_EXACT_ALARM` (Alarms & reminders), which the user grants from the workout
screen, so no `USE_EXACT_ALARM` declaration is needed.
