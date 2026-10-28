'use strict';

require('dotenv').config();

const path = require('path');

module.exports = {
  port: parseInt(process.env.PORT, 10) || 3000,
  ownerUser: process.env.OWNER_USER || 'admin',
  ownerPassword: process.env.OWNER_PASSWORD || 'CambiarEstaContraseña123!',
  sessionSecret: process.env.SESSION_SECRET || 'cambiar-clave-secreta-desarrollo',
  allowedIPs: (process.env.ALLOWED_IPS || '')
    .split(',')
    .map(s => s.trim())
    .filter(Boolean),
  dbPath: process.env.DB_PATH || path.join(__dirname, '..', 'data', 'ryxisland.db'),
  licensePrices: {
    permanente: parseFloat(process.env.LIC_PERMANENTE) || 4.00,
    semanal: parseFloat(process.env.LIC_SEMANAL) || 0.50,
    diario: parseFloat(process.env.LIC_DIARIO) || 0.20,
    mensual: parseFloat(process.env.LIC_MENSUAL) || 1.00,
  },
  currency: 'PEN',
  currencySymbol: 'S/',
  appVariants: {
    main: { name: 'Ryx Island', encrypted: true, id: 'main' },
    fb: { name: 'Ryx-Island-FB', encrypted: false, id: 'fb' },
  },
};
