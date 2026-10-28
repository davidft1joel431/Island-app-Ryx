'use strict';

const bcrypt = require('bcryptjs');
const config = require('../config');

/**
 * Autenticación del owner para el panel de control.
 * Usa express-session + bcrypt. Las credenciales vienen de .env (OWNER_USER / OWNER_PASSWORD).
 */

function hashPassword(plain) {
  return bcrypt.hashSync(plain, 10);
}

function comparePassword(plain, hash) {
  return bcrypt.compareSync(plain, hash);
}

const ownerPasswordHash = hashPassword(config.ownerPassword);

/**
 * Middleware: requiere sesión de owner activa.
 */
function requireOwner(req, res, next) {
  if (req.session && req.session.owner) {
    return next();
  }
  // Si es una petición AJAX/API, devolver 401 JSON. Si no, redirigir al login.
  if (req.accepts('json') || req.path.startsWith('/api/panel')) {
    return res.status(401).json({ error: 'no autenticado' });
  }
  return res.redirect('/panel/login');
}

function loginOwner(username, password) {
  if (username !== config.ownerUser) return false;
  return comparePassword(password, ownerPasswordHash);
}

module.exports = {
  requireOwner,
  loginOwner,
  hashPassword,
};
