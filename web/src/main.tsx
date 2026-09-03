import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthScreen } from './screens/AuthScreen';
import { AppScreen } from './screens/AppScreen';
import { AuthProvider, useAuth } from './store/auth';
import './styles.css';

function App() { const { ready, session } = useAuth(); if (!ready) return <div className="loading">Oturum kontrol ediliyor...</div>; return session ? <AppScreen /> : <AuthScreen />; }
createRoot(document.getElementById('root')!).render(<StrictMode><AuthProvider><App /></AuthProvider></StrictMode>);