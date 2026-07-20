import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { applyTheme } from './api';
import { App } from './App';
import './index.css';

applyTheme();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
