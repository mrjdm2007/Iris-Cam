# Iris Cam — source update notes

This project is an in-progress source update. It is not represented as a device-tested release.

## Changes in this update

- Kept the app ID as `com.iris.cam` and the visible app name as **Iris Cam**.
- Strengthened the Material 3 dark theme (amber accent, surface palette, Material alert dialogs) and updated camera chips/readouts to use a more consistent rounded surface treatment.
- Kept stabilization as one ON/OFF control. When enabled, Camera2 is asked for OIS and EIS together; each request is capability-checked by the camera controller. No custom 4K-to-1080p stabilization render is requested.
- Removed hard-coded camera ID assumptions from camera enumeration. Camera IDs now come from `CameraManager`, logical multi-camera devices are preferred, and labels are derived from lens metadata.
- Added a 60 fps lens compatibility check. When a selected camera cannot provide 60 fps, the app tries a compatible back-facing camera before falling back to 30 fps.
- Added a 480p preview option (alongside 720p and 1080p); recording resolution remains independent.
- Restricted 12.6 MP upscaling to detected auxiliary focal lengths. Main-camera captures are not upscaled.
- Added a selectable 2-frame or 3-frame HDR fusion bracket. The 2-frame path aligns two exposures and blends highlight/shadow regions; the 3-frame path uses the existing three-exposure fusion algorithm.
- Kept native Ultra HDR (`JPEG_R`) as a separate Camera2 capture mode. Multi-frame fusion currently produces a standard JPEG; selecting multi-frame HDR automatically switches to Phone JPEG so bracket frames are not silently discarded.
- Kept **Black and White**, added an independent highlight-selective **Glow filter**, and added a cool **Blue cinematic photo filter**. All are saved-photo filters with persistent toggles; Blue is a colour grade, not depth-based background blur. Glow applies a soft, highlight-oriented bloom to the saved JPEG without changing the live preview or the existing Golden Hour / Dream Glow looks.
- Improved laptop monitor capture to produce up to 1080p (falling back to 720p when the selected preview is smaller) at a 30 fps target, moved bitmap capture/compression off the UI thread, reused the working bitmap, and reduced per-frame copying. Resolution and frame rate are best-effort and depend on the phone and connection.
- While laptop monitoring is active in video mode, the phone requests its flat camera tone curve; the previous flat setting is restored when monitoring stops. LUT application remains on the laptop.
- Kept both Wi-Fi monitoring and USB monitoring through ADB reverse. When a pixel filter is enabled, the app switches from native Ultra HDR JPEG_R to standard JPEG so the filter is rendered rather than silently skipped; native gain-map files remain untouched when filters are off. USB instructions are shown in the app; example: `adb reverse tcp:8080 tcp:8080`, then open `http://localhost:8080/?pin=...` using the PIN shown by the app.
- Expanded laptop `.cube` parsing to accept standard 3D LUTs and 1D-only LUTs, and added shader/program error reporting. The laptop applies the LUT locally in WebGL; the phone does not apply it to the monitoring stream.
- Kept the laptop REC/CAPTURE control and the focus, zoom and exposure controls with their A/B marks and timed racks.
- Monitoring is stopped from the app when it leaves the foreground. The monitor server now recreates its worker pool on restart, so opening the monitor again after stopping it does not reuse a shut-down executor.

## Important limitations still requiring implementation or device validation

- The monitor transport is still MJPEG, not the planned hardware H.264/WebCodecs pipeline. USB ADB reverse avoids Wi-Fi transport but does not remove the JPEG capture/encode cost. Up to 1080p/30 is a target, not a guaranteed measured rate; it falls back to 720p when the chosen preview is smaller.
- Native JPEG_R files preserve the camera-generated Ultra HDR gain map. The app now opens still photos in an in-app full-screen viewer using Android ImageDecoder and reports when a JPEG_R gain map is present; actual HDR highlight rendering still depends on Android version, HWUI, display capability, and system HDR settings.
- Multi-frame HDR fusion outputs standard JPEG, not a newly-authored Ultra HDR gain-map JPEG. The two-frame fusion is an exposure-fusion implementation, not a full GCam HDR+ pipeline.
- A true depth-aware portrait blur, video VHS/Glow effects, and a dedicated H.264/WebCodecs monitor transport remain outstanding. The in-app photo viewer now uses ImageDecoder and detects native JPEG_R gain maps. A lifecycle pass now adds terminal CameraController release, shuts down Activity worker executors on destruction, and unregisters the thermal listener; broader crash and device testing remains outstanding. The Blue cinematic filter is a colour grade only and does not simulate portrait depth. The new Glow toggle is a saved-photo filter, not a live video effect. Golden Hour and Dream Glow are already available as photo looks; they are not depth-aware portrait effects. The linked zoom/focus A/B rack for Dolly Zoom is already implemented.
- Camera mode/orientation crash fixes, physical lens switching, the 0.5x–7x zoom endpoints, runtime camera recovery, and the new adaptive 1080p monitor path require physical testing across the target phone and other devices. No Android SDK/device is available in this build environment, so a Gradle APK build and camera hardware test could not be run here.

## Build

The GitHub Actions workflow builds `:app:assembleDebug` using JDK 17 and Gradle 8.7. The lifecycle-release change compiled successfully in run #58; the follow-up thermal-listener cleanup has a separate workflow run pending. The project still requires physical-device validation on the Edge 50 Neo and another Android device.
