'use strict';

const rateLimit = require('express-rate-limit');
const helmet = require('helmet');
const config = require('../config');

/**
 * Middleware de seguridad (firewall y anti-hackeo):
 *  - Helmet: cabeceras seguras
 *  - Rate limiting general y por endpoint sensible
 *  - Control de IP para el panel de owner
 *  - Bloqueo de User-Agents sospechosos y patrones de ataque en URL/body
 */

function securityHeaders() {
  return helmet({
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        styleSrc: ["'self'", "'unsafe-inline'", 'https:'],
        scriptSrc: ["'self'", "'unsafe-inline'"],
        imgSrc: ["'self'", 'data:', 'https:'],
        connectSrc: ["'self'"],
      },
    },
    crossOriginEmbedderPolicy: false,
  });
}

const apiLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'demasiadas peticiones, inténtalo más tarde' },
});

const syncLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'demasiadas sincronizaciones, inténtalo más tarde' },
});

const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'demasiados intentos de acceso, inténtalo más tarde' },
});

const suspiciousUA = [
  'sqlmap', 'nikto', 'nmap', 'masscan', 'dirbuster', 'gobuster', 'hydra',
  'metasploit', 'burpsuite', 'acunetix', 'nessus', 'openvas', 'wpscan',
];

const attackPatterns = [
  /\.\.\//, // path traversal
  /<script/i, // XSS
  /union\s+select/i, // SQLi
  /drop\s+table/i,
  /insert\s+into/i,
  /delete\s+from/i,
  /exec\s*\(/i,
  /eval\s*\(/i,
  /'/i, // comillas simples (SQLi básico)
];

function isSuspiciousUA(ua) {
  if (!ua) return true;
  const lower = ua.toLowerCase();
  return suspiciousUA.some(s => lower.includes(s));
}

function containsAttackPattern(str) {
  if (typeof str !== 'string') return false;
  return attackPatterns.some(p => p.test(str));
}

function scanObj(obj, path = '') {
  if (obj === null || obj === undefined) return false;
  if (typeof obj === 'string') {
    if (containsAttackPattern(obj)) return true;
    return false;
  }
  if (Array.isArray(obj)) return obj.some((v, i) => scanObj(v, `${path}[${i}]`));
  if (typeof obj === 'object') {
    return Object.values(obj).some((v, k) => scanObj(v, `${path}.${k}`));
  }
  return false;
}

function antiHack(req, res, next) {
  const ua = req.get('User-Agent') || '';
  if (isSuspiciousUA(ua)) {
    return res.status(403).json({ error: 'acceso denegado' });
  }

  // Escanear query params y body
  if (scanObj(req.query) || scanObj(req.body)) {
    return res.status(400).json({ error: 'petición no válida' });
  }

  // Escanear la URL
  if (containsAttackPattern(req.originalUrl || req.url)) {
    return res.status(400).json({ error: 'URL no válida' });
  }

  next();
}

function ownerIpControl(req, res, next) {
  // Solo aplica a rutas del panel (/panel/*). Si ALLOWED_IPS está vacía, se permite todo.
  if (config.allowedIPs.length === 0) return next();
  const ip = req.ip || req.connection.remoteAddress || '';
  if (!config.allowedIPs.includes(ip)) {
    return res.status(403).json({ error: 'IP no autorizada para el panel' });
  }
  next();
}

module.exports = {
  securityHeaders,
  apiLimiter,
  syncLimiter,
  authLimiter,
  antiHack,
  ownerIpControl,
};
