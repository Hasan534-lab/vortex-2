# VORTEX / VORTEX E

Native Android (Kotlin + Jetpack Compose), landscape only. One codebase, two apps:
- `operator` flavor = VORTEX (at the mixer): takes the audio from the 3.5 mm jack, runs the FFT, hosts the Wi-Fi link
- `controller` flavor = VORTEX E (in the crowd): finds the operator on the same Wi-Fi, shows the live spectrum, sends requests

Build: push this folder to GitHub (the workflow in .github builds both APKs) or open it in Android Studio.

## Wi-Fi link
Both phones join the same Wi-Fi (no internet needed). On VORTEX tap Link to see the address and the 6 digit code.
On VORTEX E tap Link, pick the operator found on the network (or type its address) and enter the code.
If the Wi-Fi drops, requests are kept on the controller and sent when the link is back.

## Not verified on real devices yet
Audio input routing from the jack, the network link and the layout on the Huawei nova Y70 still need a field test.
The scale of the kHz knob and of the band volume faders is not verified.
