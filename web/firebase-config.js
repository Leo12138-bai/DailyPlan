// Replace these four values with the Web app configuration shown in Firebase Console.
// Firebase client configuration is public; access is restricted by firestore.rules.
export const firebaseConfig = {
  apiKey: "PASTE_API_KEY",
  authDomain: "YOUR_PROJECT_ID.firebaseapp.com",
  projectId: "YOUR_PROJECT_ID",
  appId: "PASTE_WEB_APP_ID",
};

export const isFirebaseConfigured = !firebaseConfig.apiKey.startsWith("PASTE_");
