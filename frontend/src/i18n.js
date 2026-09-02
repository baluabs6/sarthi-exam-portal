import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

// Deliberately scoped to the main candidate-facing flows (header, join,
// waiting room, result) rather than every string in the app — a
// realistic starting point that's easy to extend, not a claim of full
// coverage. Admin/staff-facing screens stay English-only for now, since
// they're an internal tool, not a candidate-facing surface.
const resources = {
  en: {
    translation: {
      brand: 'SARTHI PORTAL',
      brandSub: 'National Result & Application Gateway',
      nav_about: 'About',
      nav_grievance: 'Grievance',

      join_eyebrow: 'Check Result',
      join_title: 'Enter your roll number',
      join_lead: "During high-traffic hours we place requests in a live queue so the portal stays up for everyone. You'll see your exact position and wait time on the next screen.",
      join_exam_label: 'Exam / Portal',
      join_roll_label: 'Roll Number',
      join_roll_placeholder: 'e.g. 26104578912',
      join_verification_label: 'Verification',
      join_loading_challenge: 'Loading challenge…',
      join_submit: 'Check Result',
      join_connecting: 'Connecting…',
      join_recently_checked: 'Recently checked',

      waiting_your_position: 'your position in line',
      waiting_assigning: 'assigning your position…',
      waiting_notify_me: "🔔 Notify me when it's my turn",
      waiting_notify_on: "🔔 We'll ping you the moment you're admitted.",
      waiting_share: '🔗 Share this queue link',
      waiting_share_copied: '✓ Link copied',

      result_declared: 'Declared',
      result_read_aloud: '🔊 Read result aloud',
      result_admit_card: '🎫 Download admit card',
      result_raise_grievance: 'Something wrong with this result? Raise a grievance',
    },
  },
  hi: {
    translation: {
      brand: 'सारथी पोर्टल',
      brandSub: 'राष्ट्रीय परिणाम एवं आवेदन गेटवे',
      nav_about: 'जानकारी',
      nav_grievance: 'शिकायत',

      join_eyebrow: 'परिणाम देखें',
      join_title: 'अपना रोल नंबर दर्ज करें',
      join_lead: 'अधिक ट्रैफ़िक के समय हम अनुरोधों को एक लाइव कतार में रखते हैं ताकि पोर्टल सभी के लिए चालू रहे। अगली स्क्रीन पर आपको अपनी सटीक स्थिति और प्रतीक्षा समय दिखाई देगा।',
      join_exam_label: 'परीक्षा / पोर्टल',
      join_roll_label: 'रोल नंबर',
      join_roll_placeholder: 'जैसे 26104578912',
      join_verification_label: 'सत्यापन',
      join_loading_challenge: 'लोड हो रहा है…',
      join_submit: 'परिणाम देखें',
      join_connecting: 'कनेक्ट हो रहा है…',
      join_recently_checked: 'हाल ही में देखे गए',

      waiting_your_position: 'कतार में आपकी स्थिति',
      waiting_assigning: 'आपकी स्थिति निर्धारित हो रही है…',
      waiting_notify_me: '🔔 जब मेरी बारी आए तो सूचित करें',
      waiting_notify_on: '🔔 आपके प्रवेश मिलते ही हम आपको सूचित करेंगे।',
      waiting_share: '🔗 यह कतार लिंक साझा करें',
      waiting_share_copied: '✓ लिंक कॉपी हो गया',

      result_declared: 'घोषित',
      result_read_aloud: '🔊 परिणाम सुनें',
      result_admit_card: '🎫 प्रवेश पत्र डाउनलोड करें',
      result_raise_grievance: 'इस परिणाम में कोई समस्या है? शिकायत दर्ज करें',
    },
  },
}

i18n.use(initReactI18next).init({
  resources,
  lng: localStorage.getItem('sarthi-language') || 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

export default i18n
