import React from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import GrievanceModal from '../components/GrievanceModal.jsx'

/** Reached via the QR code printed on a grievance ticket, or a direct /track/{ticketRef} link — opens straight to the tracker, pre-filled. */
export default function TrackPage() {
  const { ticketRef } = useParams()
  const navigate = useNavigate()
  return (
    <GrievanceModal
      onClose={() => navigate('/')}
      initialTab="track"
      initialTrackRef={ticketRef}
    />
  )
}
