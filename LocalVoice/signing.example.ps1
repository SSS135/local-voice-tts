# Copy to signing.local.ps1 and edit the private path. Keep it outside shared source archives.
# Dot-source this file in a private PowerShell session, then run: .\build.ps1 -Tasks ':app:assembleRelease'
# Only the release build type uses this key; debug builds always keep the Android debug key.
$env:LOCAL_VOICE_KEYSTORE = 'C:\private\LocalVoice\local-voice.keystore'
$env:LOCAL_VOICE_KEY_ALIAS = 'localvoice'
$secret = Read-Host 'Keystore password' -AsSecureString
$credential = New-Object System.Management.Automation.PSCredential('localvoice', $secret)
$env:LOCAL_VOICE_STORE_PASSWORD = $credential.GetNetworkCredential().Password
# If the key password differs, read it separately into LOCAL_VOICE_KEY_PASSWORD.
# Remove the environment values after the build, or close this PowerShell process.
