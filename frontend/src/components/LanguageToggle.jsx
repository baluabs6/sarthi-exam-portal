import React from 'react'
import { useTranslation } from 'react-i18next'

export default function LanguageToggle() {
  const { i18n } = useTranslation()

  function toggle() {
    const next = i18n.language === 'hi' ? 'en' : 'hi'
    i18n.changeLanguage(next)
    localStorage.setItem('sarthi-language', next)
  }

  return (
    <button className="theme-toggle" onClick={toggle} aria-label="Switch language" title="Switch language">
      {i18n.language === 'hi' ? 'EN' : 'हि'}
    </button>
  )
}
