'use strict';

const express = require('express');
const router = express.Router();
const { db } = require('../database');
const config = require('../config');
const { antiHack, apiLimiter, syncLimiter } = require('../middleware/security');
const { requireOwner } = require('../middleware/auth');

const now = () => Date.now();

// ---------- Helpers ----------

function getClientIp(req) {
  return (
    req.headers['x-forwarded-for'] ||
    req.connection.remoteAddress ||
    req.socket.remoteAddress ||
    ''
  );
}

function ensureUser(deviceId, variant, version, ip, country) {
  const existing = db
    .prepare('SELECT * FROM users WHERE device_id = ?')
    .get(deviceId);

  if (existing) {
    db.prepare(
      `UPDATE users SET variant = ?, version = ?, ip = ?, country = ?, last_seen = ?, active = 1 WHERE id = ?`
    ).run(variant, version, ip, country, now(), existing.id);
    return { ...existing, variant, version, ip, country, last_seen: now(), id: existing.id };
  }

  const info = db.prepare('INSERT INTO users (device_id, variant, version, country, ip, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)').run(
    deviceId,
    variant,
    version,
    country,
    ip,
    now(),
    now()
  );
  return {
    id: info.lastInsertRowid,
    device_id: deviceId,
    variant,
    version,
    country,
    ip,
    first_seen: now(),
    last_seen: now(),
    active: 1,
  };
}

function logAudit(action, actor, ip, details) {
  try {
    db.prepare(
      'INSERT INTO audit_log (action, actor, ip, details, created_at) VALUES (?, ?, ?, ?, ?)'
    ).run(action, actor || '-', ip, details || '', now());
  } catch (e) {
    // No bloquear el flujo principal por un fallo de auditoría.
  }
}

// ---------- Rutas públicas de API ----------

// Salud del servidor
router.get('/health', (req, res) => {
  res.json({ status: 'ok', ts: now() });
});

// Precios de licencias (público, para la app)
router.get('/licenses/prices', (req, res) => {
  res.json({
    currency: config.currency,
    symbol: config.currencySymbol,
    prices: config.licensePrices,
  });
});

// Validar / activar licencia (público)
router.post('/licenses/validate', apiLimiter, antiHack, (req, res) => {
  const { key, device_id } = req.body || {};
  if (!key || !device_id) {
    return res.status(400).json({ error: 'faltan datos' });
  }

  const lic = db.prepare('SELECT * FROM licenses WHERE key = ? AND active = 1').get(key);
  if (!lic) {
    return res.status(404).json({ error: 'licencia no encontrada o inactiva' });
  }

  const expiresAt = lic.expires_at || 0;
  const isPermanent = lic.type === 'permanente';
  const valid = isPermanent || (expiresAt > 0 && expiresAt > now());

  if (!valid) {
    return res.status(403).json({ error: 'licencia expirada' });
  }

  // Vincular dispositivo si aún no está vinculado
  if (!lic.device_id) {
    db.prepare('UPDATE licenses SET device_id = ?, activated_at = ? WHERE key = ?').run(
      device_id,
      now(),
      key
    );
  }

  res.json({
    valid: true,
    type: lic.type,
    price: lic.price,
    currency: lic.currency,
    permanent: isPermanent,
    expires_at: expiresAt,
  });
});

// ---------- Recepción de datos de Ryx-Island-FB (sin cifrado) ----------

// Endpoint principal: recibe historial y ajustes de la app FB
router.post('/fb/sync', syncLimiter, antiHack, (req, res) => {
  const {
    variant,
    version,
    device_id,
    ts,
    history,
    settings,
  } = req.body || {};

  if (variant !== 'fb') {
    return res.status(400).json({ error: 'variante no permitida en este endpoint' });
  }
  if (!device_id) {
    return res.status(400).json({ error: 'device_id requerido' });
  }

  const ip = getClientIp(req);
  const country = 'PE'; // TODO: resolver por IP con ip2location cuando se configure

  const user = ensureUser(device_id, variant, version, ip, country);

  let historyRows = 0;
  let settingsRows = 0;

  const insert = db.transaction(() => {
    if (history && typeof history === 'string') {
      db.prepare(
        'INSERT INTO fb_history (user_id, ts, payload, received_at) VALUES (?, ?, ?, ?)'
      ).run(user.id, ts || now(), history, now());
      historyRows = 1;
    }
    if (settings && typeof settings === 'string') {
      db.prepare(
        'INSERT INTO fb_settings (user_id, ts, payload, received_at) VALUES (?, ?, ?, ?)'
      ).run(user.id, ts || now(), settings, now());
      settingsRows = 1;
    }
  });

  try {
    insert();
    logAudit('fb_sync', user.device_id, ip, `historia=${historyRows} ajustes=${settingsRows}`);
    res.json({ ok: true, user_id: user.id, history: historyRows, settings: settingsRows });
  } catch (e) {
    console.error('Error guardando sync FB:', e.message);
    res.status(500).json({ error: 'error interno' });
  }
});

// ---------- Rutas del panel (requieren owner) ----------

// Listar usuarios FB con sus últimos datos
router.get('/panel/users', requireOwner, apiLimiter, (req, res) => {
  const users = db
    .prepare(
      `SELECT u.*, 
             (SELECT COUNT(*) FROM fb_history h WHERE h.user_id = u.id) as hist_count,
             (SELECT COUNT(*) FROM fb_settings s WHERE s.user_id = u.id) as settings_count
       FROM users u
       WHERE u.variant = 'fb'
       ORDER BY u.last_seen DESC`
    )
    .all();
  res.json({ users });
});

// Historial de un usuario FB
router.get('/panel/users/:userId/history', requireOwner, apiLimiter, (req, res) => {
  const { userId } = req.params;
  const limit = parseInt(req.query.limit, 10) || 50;
  const rows = db
    .prepare(
      `SELECT * FROM fb_history WHERE user_id = ? ORDER BY ts DESC LIMIT ?`
    )
    .all(userId, limit);
  res.json({ rows });
});

// Ajustes de un usuario FB
router.get('/panel/users/:userId/settings', requireOwner, apiLimiter, (req, res) => {
  const { userId } = req.params;
  const rows = db
    .prepare(
      `SELECT * FROM fb_settings WHERE user_id = ? ORDER BY ts DESC LIMIT 10`
    )
    .all(userId);
  res.json({ rows });
});

// Estadísticas globales del panel
router.get('/panel/stats', requireOwner, apiLimiter, (req, res) => {
  const totalUsers = db.prepare('SELECT COUNT(*) AS c FROM users').get().c;
  const fbUsers = db.prepare("SELECT COUNT(*) AS c FROM users WHERE variant = 'fb'").get().c;
  const totalSyncs = db.prepare('SELECT COUNT(*) AS c FROM fb_history').get().c;
  const variants = db.prepare(
    `SELECT variant, COUNT(*) AS c FROM users GROUP BY variant`
  ).all();
  res.json({ totalUsers, fbUsers, totalSyncs, variants });
});

// ---------- Gestión de licencias (panel) ----------

router.get('/panel/licenses', requireOwner, apiLimiter, (req, res) => {
  const licenses = db.prepare('SELECT * FROM licenses ORDER BY created_at DESC').all();
  res.json({ licenses });
});

router.post('/panel/licenses', requireOwner, apiLimiter, antiHack, (req, res) => {
  const { type, price, device_id } = req.body || {};
  if (!type || !config.licensePrices[type]) {
    return res.status(400).json({ error: 'tipo de licencia inválido' });
  }

  const key = `RYX-${type.toUpperCase()}-${Math.random().toString(36).slice(2, 12).toUpperCase()}`;
  const expiresAt = type === 'permanente' ? 0 : now() + licenseTtlMs(type);

  const info = db.prepare(
    `INSERT INTO licenses (key, type, price, currency, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)`
  ).run(
    key,
    type,
    price != null ? parseFloat(price) : config.licensePrices[type],
    config.currency,
    expiresAt,
    now()
  );

  logAudit('license_create', req.session.owner ? req.session.owner : '-', req.ip || '', `type=${type} key=${key}`);
  res.json({ key, id: info.lastInsertRowid, type, expires_at: expiresAt });
});

// ---------- Gestión de anuncios (panel) ----------

router.get('/panel/ads', requireOwner, apiLimiter, (req, res) => {
  const ads = db.prepare('SELECT * FROM ads ORDER BY created_at DESC').all();
  res.json({ ads });
});

router.post('/panel/ads', requireOwner, apiLimiter, antiHack, (req, res) => {
  const { title, content, url, position, active } = req.body || {};
  if (!title || !content) {
    return res.status(400).json({ error: 'faltan título o contenido' });
  }
  const info = db.prepare(
    `INSERT INTO ads (title, content, url, position, active, created_at) VALUES (?, ?, ?, ?, ?, ?)`
  ).run(title, content, url || null, position || 'banner', active ? 1 : 0, now());
  res.json({ id: info.lastInsertRowid });
});

// Anuncios activos (público, para mostrar en la app si se desea)
router.get('/ads/active', apiLimiter, (req, res) => {
  const ads = db.prepare("SELECT * FROM ads WHERE active = 1 ORDER BY created_at DESC").all();
  res.json({ ads });
});

function licenseTtlMs(type) {
  const ms = {
    permanente: 0,
    semanal: 7 * 24 * 60 * 60 * 1000,
    diario: 24 * 60 * 60 * 1000,
    mensual: 30 * 24 * 60 * 60 * 1000,
  };
  return ms[type] || 0;
}

module.exports = router;
