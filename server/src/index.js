'use strict';

const express = require('express');
const session = require('express-session');
const cors = require('cors');
const config = require('./config');
const { db, init } = require('./database');
const {
  securityHeaders,
  apiLimiter,
  syncLimiter,
  authLimiter,
  antiHack,
} = require('./middleware/security');
const apiRouter = require('./routes/api');
const panelRouter = require('./routes/panel');

const app = express();

// Inicializar base de datos
init();

// ---------- Middleware global ----------
app.use(securityHeaders());
app.use(cors({ origin: true, credentials: true }));
app.use(express.json({ limit: '2mb' }));
app.use(express.urlencoded({ extended: true, limit: '2mb' }));

app.use(
  session({
    secret: config.sessionSecret,
    resave: false,
    saveUninitialized: false,
    cookie: {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      maxAge: 8 * 60 * 60 * 1000, // 8 horas
    },
    name: 'ryx_sid',
  })
);

// ---------- Rutas públicas ----------
app.use('/api', apiRouter);

// ---------- Panel de control (solo owner) ----------
app.use('/panel', panelRouter);

// ---------- Login / logout ----------
app.post('/api/panel/login', authLimiter, antiHack, (req, res) => {
  const { username, password } = req.body || {};
  const { loginOwner } = require('./middleware/auth');
  if (!username || !password) {
    return res.status(400).json({ error: 'faltan credenciales' });
  }
  if (loginOwner(username, password)) {
    req.session.owner = { username, loggedInAt: Date.now() };
    return res.json({ ok: true, redirect: '/panel' });
  }
  res.status(401).json({ error: 'credenciales inválidas' });
});

app.post('/api/panel/logout', (req, res) => {
  req.session.destroy(() => {
    res.clearCookie('ryx_sid');
    res.json({ ok: true });
  });
});

// ---------- Página de inicio (público) ----------
app.get('/', (req, res) => {
  res.json({
    name: 'Ryx Island Server',
    version: '1.0.0',
    status: 'online',
    endpoints: {
      health: '/api/health',
      licensePrices: '/api/licenses/prices',
      fbSync: '/api/fb/sync',
      panel: '/panel (solo owner)',
    },
  });
});

// ---------- Manejo de errores ----------
app.use((err, req, res, next) => {
  console.error('Error no controlado:', err.message);
  res.status(500).json({ error: 'error interno del servidor' });
});

app.use((req, res) => {
  res.status(404).json({ error: 'ruta no encontrada' });
});

const server = app.listen(config.port, () => {
  console.log(`\n[Ryx Island Server] escuchando en http://localhost:${config.port}`);
  console.log(`[Panel] http://localhost:${config.port}/panel/login`);
  console.log(`[Owner] ${config.ownerUser}`);
  console.log(`[DB] ${config.dbPath}\n`);
});

process.on('uncaughtException', (err) => {
  console.error('Excepción no capturada:', err.message);
});

process.on('unhandledRejection', (reason) => {
  console.error('Promesa rechazada no controlada:', reason);
});

module.exports = { app, server };
