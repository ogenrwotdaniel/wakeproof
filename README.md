# Pomodoro Timer App

A complete Android application implementing the Pomodoro Technique with nature sounds and home screen widget support.

## Features

### ✅ 1. Pomodoro Technique Implementation
- **Work Sessions**: 25-minute focused work periods
- **Short Breaks**: 5-minute breaks after each work session
- **Long Breaks**: 15-minute breaks after completing 4 work sessions
- **Session Tracking**: Displays current session progress (1/4, 2/4, etc.)
- **Auto-progression**: Automatically transitions between work and break periods

### ✅ 2. Nature Sounds During Activity
- **Work Period Audio**: Plays soothing nature sounds during work sessions
- **Sound Toggle**: Users can enable/disable nature sounds via in-app switch
- **Silent Breaks**: No audio during break periods (as requested)
- **Looping Audio**: Continuous nature sound playback during work sessions
- **Volume Control**: Optimized volume levels for focus

### ✅ 3. Silent During Break Time
- **Break Silence**: Audio automatically stops during all break periods
- **Smart Audio Management**: Distinguishes between work and break periods
- **Notification Only**: Only visual notifications during breaks

### ✅ 4. Home Screen Widget
- **Live Countdown**: Real-time display of remaining time
- **Session Indicator**: Shows current session type (Work/Break)
- **Session Progress**: Displays session count (1/4, 2/4, etc.)
- **Visual Feedback**: Different colors for work vs break periods
- **Auto-update**: Refreshes every second during active timer

## Technical Implementation

### Architecture
- **Service-based Timer**: Uses Android foreground service for reliable timing
- **Broadcast Communication**: Real-time updates between service and UI
- **Widget Integration**: Home screen widget receives live timer updates
- **MediaPlayer Integration**: Handles nature sound playback

### Key Components
1. **MainActivity**: Main user interface with controls
2. **PomodoroService**: Background service managing timer logic
3. **PomodoroWidget**: Home screen widget implementation
4. **PomodoroUpdateReceiver**: Handles widget updates

### Audio Features
- **MediaPlayer**: Manages nature sound playback
- **Sound State Management**: Tracks audio enable/disable state
- **Work Period Detection**: Only plays audio during work sessions
- **Resource Management**: Proper cleanup to prevent memory leaks

## Usage

1. **Start Timer**: Tap "Start Timer" to begin a 25-minute work session
2. **Toggle Sounds**: Use the switch to enable/disable nature sounds
3. **Control Timer**: Pause or stop the timer as needed
4. **Add Widget**: Long-press home screen → Widgets → Pomodoro Timer
5. **Monitor Progress**: Widget shows countdown and session progress

## Installation

1. Build the project in Android Studio
2. Install on device (API 24+)
3. Add widget to home screen for quick access
4. For nature sounds, add audio files to `res/raw/` directory

## Customization

### Adding Nature Sounds
1. Place MP3/OGG files in `app/src/main/res/raw/`
2. Update `PomodoroService.initializeMediaPlayer()` method
3. Uncomment MediaPlayer initialization code
4. See `NATURE_SOUNDS_GUIDE.md` for detailed instructions

### Timing Adjustments
Modify timing constants in `PomodoroService`:
- `WORK_DURATION`: Work session length
- `SHORT_BREAK_DURATION`: Short break length  
- `LONG_BREAK_DURATION`: Long break length

## Requirements

- **Android API 24+** (Android 7.0)
- **Permissions**: Foreground service, wake lock, notifications
- **Storage**: ~5MB (plus audio files if added)

## Features Summary

✅ **Pomodoro Technique**: Complete implementation with 4 work sessions and breaks  
✅ **Nature Sounds**: Audio during work periods only  
✅ **Silent Breaks**: No audio during break time  
✅ **Home Widget**: Live countdown display with session progress  
✅ **Background Service**: Reliable timing even when app is closed  
✅ **Notification System**: Persistent notification during active timer  
✅ **Session Tracking**: Visual progress indicator (1/4, 2/4, etc.)  
✅ **Auto-progression**: Seamless transition between work and break periods  

The app successfully implements all requested features with a clean, intuitive interface and robust background functionality.
