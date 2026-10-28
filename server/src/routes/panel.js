'use strict';

const express = require('express');
const router = express.Router();
const path = require('path');
const { requireOwner } = require('../middleware/auth');
const { ownerIpControl } = require('../middleware/security');

// Todas las rutas del panel requieren owner y control de IP
router.use(requireOwner);
router.use(ownerIpControl);

router.get('/login', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'views', 'login.html'));
});

router.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'views', 'dashboard.html'));
});

router.get('/users', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'views', 'users.html'));
});

router.get('/licenses', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'views', 'licenses.html'));
});

router.get('/ads', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'views', 'ads.html'));
});

module.exports = router;
