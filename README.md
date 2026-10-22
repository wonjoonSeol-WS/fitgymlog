# FitGymLog

![The band's music screen showing the current set next to the workout screen](docs/images/fitgymlog.jpg)

A workout log for Android that you can run from a Galaxy Fit band. Press play on the band to complete a set, feel a buzz
when rest is over, and load the bar by tapping plates.

Guide and screenshots: https://wonjoonseol-ws.github.io/fitgymlog/ (한국어: https://wonjoonseol-ws.github.io/fitgymlog/ko/)
Download: [latest release](https://github.com/wonjoonSeol-WS/fitgymlog/releases/latest)

- Band remote through the music controls, and a rest-over buzz on the wrist
- Plate-by-plate weight entry and a reps number pad
- Cardio in minutes (treadmill, stair machine, bike), each saved to Health Connect as its own session
- Ready-made programs (StrongLifts 5x5, full body, upper/lower, push/pull/legs), plus your own routines
- Form guide videos and articles for the built-in exercises; add any YouTube or web link
- Two-pane layouts on an unfolded Galaxy Fold
- Health Connect (Samsung Health), optional Google Drive sync, Hevy and Strong CSV import and export
- English and Korean

Android 13 or newer. Tested with a Galaxy Fit3 and a Galaxy Z Fold.

## How the band controls the app

The Galaxy Fit3 cannot run third-party apps, but its music screen controls whatever is playing on the phone. FitGymLog
uses that screen as a remote.

1. **A media session with nothing playing.** While a workout is open, `WorkoutService` (a foreground service of type
   `mediaPlayback`) holds an active `MediaSession` in the `PLAYING` state. No audio is played. The session advertises
   play, pause, next and previous.
2. **The set is the song.** The session's metadata carries the current set: the title is the exercise and set
   ("Squat (Barbell) 1/5"), the artist is what to lift ("60 kg x 8"), the album is the workout. The band shows the
   title and artist on its music screen.
3. **Buttons become workout actions.** Galaxy Wearable sends the band's presses to the phone as media transport
   controls on the active session, so they arrive in `MediaSession.Callback`:

   | Band button | Callback | What FitGymLog does |
   |---|---|---|
   | Play or pause | `onPlay`, `onPause` | Completes the set the band is showing and starts the rest timer |
   | Next | `onSkipToNext` | Moves to the next exercise, wrapping to the first |
   | Previous | `onSkipToPrevious` | Takes one rep off the set on the band; below zero it goes back to the planned reps |

   The screen and the band both pass the set they expect to complete, so a tap and a band press at the same moment
   cannot complete two sets.
4. **Rest on the band.** While resting, the title counts down ("Rest 1:55") over the set just done, which is the set
   previous corrects. The title changes in five-second steps timed to the end of the rest, so the band gets few updates.
   The session's duration is the rest length and its position runs at normal speed from 0:00, so the band's progress
   ring around the pause button fills as the rest goes by, like a song, without the app sending it anything per second.
5. **The buzz.** When rest ends, an exact alarm (`AlarmManager.setExactAndAllowWhileIdle`) fires `RestOverReceiver`,
   which posts a high-priority notification with vibration. Galaxy Wearable forwards notifications from apps switched
   on in its Notifications settings, so the band buzzes and shows the next set.

**On the phone, by design:** because the workout is a playing media session, the phone treats it like music. On One UI
the current set shows as a chip at the top left of the status bar, and tapping it opens the media player with previous,
pause and next; tapping the player opens FitGymLog. That player, the lock screen controls and the notification shade all drive the same session as the
band, so pause there completes a set too.

<img src="docs/images/media-player.webp" width="360" alt="The workout in Android's media player: Squat (Barbell) 2/5, 60 kg x 8, with previous, pause and next">

**The catch:** Android sends media buttons to the app that started playing most recently. With YouTube Music,
Spotify or a video playing, the band's buttons can switch between that app and FitGymLog. The rest buzz is a
notification, so it always arrives.

**Testing without a band:** the media player in the notification shade uses the same transport controls, so it stands
in for the band on an emulator. `adb shell cmd media_session dispatch` sends media key events instead, which do not
reach a session that plays no audio.

## Your data

Workouts are stored on the phone. There is no account, no ads and no analytics. Health Connect writes and Google Drive
sync are optional; sync keeps one file in the app's hidden folder of your own Drive. Hevy and Strong CSV import and
export mean you can leave at any time. See the [privacy policy](https://wonjoonseol-ws.github.io/fitgymlog/privacy.html).

## Building

Requires JDK 17 and the Android SDK.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

A release build is signed with the key described in `~/.android/fitgymlog/signing.properties` when that file exists,
and with the debug key otherwise.

## 한국어

Galaxy Fit 밴드로 조작하는 Android 운동 기록 앱입니다. 밴드는 다른 앱을 실행할 수 없지만 휴대폰의 음악을 조작할 수 있어서,
FitGymLog는 운동 중에 소리 없이 "재생 중"인 미디어 세션을 열어 둡니다. 지금 세트가 곡 제목과 아티스트 자리에 보이고,
재생/일시정지는 세트 완료, 다음은 다음 운동, 이전은 횟수 하나 빼기로 받습니다. 휴식 중에는 제목에 남은 시간이 5초 단위로(1:55, 1:50)
보이고, 휴식 시간이 곡 길이가 되어 일시정지 버튼 둘레의 진행 링이 음악처럼 채워집니다. 휴식이 끝나면 정확한 알람으로 알림을 띄우고,
Galaxy Wearable이 이 알림을 밴드로 보내 진동합니다. 그래서 휴대폰에서도 음악처럼 보입니다. 상태 표시줄 왼쪽 위에 지금 세트가
뜨고, 누르면 미디어 플레이어가 열리며 플레이어를 누르면 FitGymLog가 열립니다. 이 플레이어와 잠금 화면 조작도 밴드와 똑같이 동작하는 의도된 설계입니다. 다른 앱이
음악이나 영상을 재생하면 버튼이 그 앱과 오갈 수 있습니다.

## License

[MIT](LICENSE). The bundled Barlow Semi Condensed font is under the SIL Open Font License 1.1, see
[third_party/barlow/OFL.txt](third_party/barlow/OFL.txt).
