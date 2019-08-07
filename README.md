This project implements audio player for android platform with options to modify audio ('speed player') - change playback speed, boost volume, skip silence, reduce noise. It requires advanced knowledge of android(java), jni, ndk, c++, gradle.

Note this is a "skeleton" app specifically designed to test the audio component, not intended for "real" end users.

<img width='300' src='https://i.imgur.com/qEqmC7Ll.png' />

## SETUP
Download and import into Android Studio

Required NDK = Download android NDK r19c (you can google that)

Set `sdk.dir` and `ndk.dir` in `local.properties`

## CURRENT STATE
- project contains predefined sample files - (`.m4a`, `.mp3`, `.flac`, `.ogg`)
- all audio effects are working for `.mp3`, `.flac`, `.ogg`
- streamed m4a files don't work (change playback speed, boost volume, skip silence, reduce noise)
- downloaded m4a files sometimes work, but even if it works, there is sometimes crackling/noise if speed works for downloaded file > audio is not smooth

* Build target SDK version: Latest - currently "28"
* Build compile SDK version: "28"
* Minimal SDK version: "14"
* Build tools version:"27.0.3"
* Project build system: gradle	
* Gradle plugin version: "3.1.3"
* NDK version:"r19c"

## FIRST MILESTONE
1. Remove `TODO remove this line` from the bottom of this file and commit your changes
1. Change primary app color to `#2196F3`
1. Change primary dark app color to `#1976D2`
1. Change accent app color to `#FF5252`
1. Commit your changes
1. Share screenshot of app with updated color palette
1. Share apk with updated color palette


## PROJECT GOALS
1. Fix speed player implementation to support `.m4a` audio files with both downloaded and streaming audio, and standard control. Any combination of speed, boost volume, skip silence, and reduce noise settings should be supported (already possible for all the other audio formats)
1. Provide basic comments for all your new code you generate, and ideally existing code if you had trouble to figure out what it does. Add instructions to this README on build procedure, if any further steps are needed.

## EXISTING FUNCTIONALITY TO RETAIN
1. Ensure your solution works on 32-bit devices (ARM and x86), across all supported SDK versions (SDK 14+)
1. Ensure other file formats still work as before, i.e. any audio effect setting on `.mp3`, `.flac`, `.ogg` formats is still working in the above conditions

## CODE CHANGES NEEDED
1. Please develop on a new git branch
1. The player is implemented in `src/main/java/fm/player/mediaplayer/player/CustomMediaPlayer.java`. For your Java code updates, please only edit `fm.player.mediaplayer` package
1. native code is located in `src/main/jni`
1. Please retain the current build process and min/target/compile SDK version, version of NDK, gradle plugin and `buildToolsVersion`. If you need to change any dependency version, please check with us first (as it may break our main app). These should *not* be changed.

If not sure about anything, discuss with us via Slack - we're happy to help!

## TODO remove this line
