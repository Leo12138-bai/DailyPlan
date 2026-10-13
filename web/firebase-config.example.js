// Copy this file to firebase-config.js and replace the values from Firebase Console.
// This web configuration is public client configuration; Firestore Rules protect user data.
export const firebaseConfig = {
  apiKey: "PASTE_API_KEY",
  authDomain: "YOUR_PROJECT_ID.firebaseapp.com",
  projectId: "YOUR_PROJECT_ID",
  appId: "PASTE_WEB_APP_ID",
};

export const isFirebaseConfigured = !firebaseConfig.apiKey.startsWith("PASTE_");
