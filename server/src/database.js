'use strict';

const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

const config = require('./config');

const dbDir = path.dirname(config.dbPath);
if (!fs.existsSync(dbDir)) {
  fs.mkdirSync(dbDir, { recursive: true });
}

const db = new Database(config.dbPath);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

function init() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id TEXT NOT NULL UNIQUE,
      variant TEXT NOT NULL CHECK (variant IN ('main','fb')),
      version TEXT,
      country TEXT,
      ip TEXT,
      first_seen INTEGER NOT NULL,
      last_seen INTEGER NOT NULL,
      license_type TEXT DEFAULT 'gratis',
      license_expires_at INTEGER DEFAULT 0,
      license_key TEXT,
      active INTEGER DEFAULT 1
    );

    CREATE TABLE IF NOT EXISTS fb_history (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      ts INTEGER NOT NULL,
      payload TEXT NOT NULL,
      received_at INTEGER NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS fb_settings (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      ts INTEGER NOT NULL,
      payload TEXT NOT NULL,
      received_at INTEGER NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS licenses (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      key TEXT NOT NULL UNIQUE,
      type TEXT NOT NULL CHECK (type IN ('permanente','semanal','diario','mensual')),
      price REAL NOT NULL,
      currency TEXT DEFAULT 'PEN',
      device_id TEXT,
      user_id INTEGER,
      activated_at INTEGER DEFAULT 0,
      expires_at INTEGER DEFAULT 0,
      active INTEGER DEFAULT 1,
      created_at INTEGER NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
    );

    CREATE TABLE IF NOT EXISTS ads (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      title TEXT NOT NULL,
      content TEXT NOT NULL,
      url TEXT,
      active INTEGER DEFAULT 1,
      position TEXT DEFAULT 'banner',
      created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS audit_log (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      action TEXT NOT NULL,
      actor TEXT,
      ip TEXT,
      details TEXT,
      created_at INTEGER NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_fb_history_user ON fb_history(user_id, ts DESC);
    CREATE INDEX IF NOT EXISTS idx_fb_settings_user ON fb_settings(user_id, ts DESC);
    CREATE INDEX IF NOT EXISTS idx_users_variant ON users(variant);
    CREATE INDEX IF NOT EXISTS idx_users_device ON users(device_id);
    CREATE INDEX IF NOT EXISTS idx_licenses_key ON licenses(key);
    CREATE INDEX IF NOT EXISTS idx_licenses_device ON licenses(device_id);
  `);
}

module.exports = { db, init };
