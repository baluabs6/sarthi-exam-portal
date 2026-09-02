import React, { useEffect, useState } from 'react'
import { Routes, Route } from 'react-router-dom'
import JoinPage from './pages/JoinPage.jsx'
import WaitingRoom from './pages/WaitingRoom.jsx'
import ResultPage from './pages/ResultPage.jsx'
import StatusPill from './components/StatusPill.jsx'
import AboutModal from './components/AboutModal.jsx'
import BuildInfoModal from './components/BuildInfoModal.jsx'
import GrievanceModal from './components/GrievanceModal.jsx'
import FaqWidget from './components/FaqWidget.jsx'
import AdminDashboard from './pages/AdminDashboard.jsx'
import TrackPage from './pages/TrackPage.jsx'
import ThemeToggle from './components/ThemeToggle.jsx'
import LanguageToggle from './components/LanguageToggle.jsx'
import { useTranslation } from 'react-i18next'

export default function App() {
  const { t } = useTranslation()
  const [showAbout, setShowAbout] = useState(false)
  const [showBuildInfo, setShowBuildInfo] = useState(false)
  const [showGrievance, setShowGrievance] = useState(false)
  const [grievanceContext, setGrievanceContext] = useState({ rollNumber: '', examId: '' })

  function openGrievance(rollNumber = '', examId = '') {
    setGrievanceContext({ rollNumber, examId })
    setShowGrievance(true)
  }

  useEffect(() => {
    // Show the build-info popup once per browser session, on first load.
    const seen = sessionStorage.getItem('sarthi-build-info-seen')
    if (!seen) {
      setShowBuildInfo(true)
      sessionStorage.setItem('sarthi-build-info-seen', '1')
    }
  }, [])

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark" />
          {t('brand')}
          <span className="brand-sub">{t('brandSub')}</span>
        </div>
        <div className="header-actions">
          <ThemeToggle />
          <LanguageToggle />
          <button className="btn-ghost btn-about" onClick={() => openGrievance()}>{t('nav_grievance')}</button>
          <button className="btn-ghost btn-about" onClick={() => setShowAbout(true)}>{t('nav_about')}</button>
          <StatusPill />
        </div>
      </header>

      <main className="main">
        <Routes>
          <Route path="/" element={<JoinPage />} />
          <Route path="/queue/:queueId" element={<WaitingRoom />} />
          <Route path="/result/:rollNumber" element={<ResultPage onRaiseGrievance={openGrievance} />} />
          <Route path="/admin" element={<AdminDashboard />} />
          <Route path="/track/:ticketRef" element={<TrackPage />} />
        </Routes>
      </main>

      {showAbout && <AboutModal onClose={() => setShowAbout(false)} />}
      {showBuildInfo && <BuildInfoModal onClose={() => setShowBuildInfo(false)} />}
      {showGrievance && (
        <GrievanceModal
          onClose={() => setShowGrievance(false)}
          defaultRollNumber={grievanceContext.rollNumber}
          defaultExamId={grievanceContext.examId}
        />
      )}
      <FaqWidget />
    </div>
  )
}
