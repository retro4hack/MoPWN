# MoPWN
Inspect and test your applications with just your phone!

MoPWN is an application I'm creating with Antigravity+Android Studio: it aims to help Penetration Testers to perform their checks and many operations directly from mobile.
It's still in development and I will try to add as many functionalities as I can. 

## Current Functionalities
### Free to Use
* Lists the installed applications, enabling the user to choose to show only user apps or all the apps (including the system ones): search functionality included ;)
* Inspects the application and the manifest, returning all the relevant information, permissions, flags, tecnologies, obfuscation probability etc.
* Finds all the exported activities, giving the possibility to inject data and extras, call the activity with specific actions 
* Decompiles the application classes using JADX external library
* Search for secrets, hardcoded strings and indicators
### Root Required
* Navigate the data of the target application, download any file you need to access it easily
* Dump or share the APK(s) (including bundles)
* Manage the installation and execution of your Frida Server
* Shows the current foreground class in a permanent notification (if the service is running, clearly)

## Troubleshooting
When you clone your repository it will likely fail while building due to the missing sdk. local.properties is a file that should not be shared, so you will have to created by yourself. 
Just create local.properties in the root of the project and insert this line: sdk.dir=< path-to-SDK >

---
If you have any idea, feel free to create a pull request, suggest anything you would like to see on the application, or fork and do it yourself if you prefer
Remember to use this application for legal activities or for educational purposes ;)

And if you want to support this project, you can leave a tip here: any help is appreciated <3

https://www.paypal.me/Retro4Hack