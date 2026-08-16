import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { applyTheme } from './preferences';
import { applyUiScale } from './uiScale';
import { App } from './App';
import './index.css';

applyTheme();
applyUiScale();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
