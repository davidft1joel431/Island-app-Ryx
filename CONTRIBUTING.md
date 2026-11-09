# Contribuir a Ryx Isla

¡Gracias por tu interés en contribuir a **Ryx Isla**! Las sugerencias, reportes de errores, mejoras de código y aportes a la documentación ayudan a que el proyecto siga creciendo.

Antes de abrir un issue o enviar un pull request, revisa estas pautas.

## Formas de contribuir

- Reportar errores con pasos claros para reproducirlos.
- Proponer funciones y mejoras concretas.
- Mejorar la interfaz Jetpack Compose, las animaciones y la accesibilidad.
- Probar compatibilidad con distintas versiones de Android y fabricantes.
- Añadir o mejorar pruebas automatizadas.
- Corregir y traducir documentación.
- Refactorizar código cuando reduzca complejidad sin alterar el comportamiento esperado.

## Preparar el entorno

### Requisitos

- JDK 17.
- Android Studio.
- Android SDK 36.
- Dispositivo o emulador con Android 8.0 o posterior.

### Compilar en Windows

```powershell
.\gradlew.bat assembleDebug
```

### Compilar en Linux o macOS

```bash
chmod +x gradlew
./gradlew assembleDebug
```

### Instalar en un dispositivo conectado

```bash
./gradlew installDebug
```

### Ejecutar pruebas unitarias

```bash
./gradlew test
```

## Antes de enviar un pull request

- [ ] El proyecto compila utilizando el Gradle Wrapper.
- [ ] Se probaron los cambios en un dispositivo o emulador cuando corresponde.
- [ ] Los flujos de superposición y notificaciones se verificaron si fueron modificados.
- [ ] Se añadieron o actualizaron pruebas cuando el cambio lo requiere.
- [ ] La documentación de las funciones visibles se actualizó en `README.md` o en el documento correspondiente.
- [ ] No se incluyeron APKs generados, carpetas de compilación, claves, tokens ni secretos.
- [ ] Las capturas y los registros no contienen información privada.
- [ ] El pull request explica el motivo del cambio y cómo se verificó.

## Estilo de código

- Prefiere cambios pequeños y enfocados.
- Mantén el estado de la interfaz explícito y consciente del ciclo de vida.
- Reutiliza modelos, repositorios y componentes existentes cuando sea apropiado.
- Evita añadir abstracciones sin una necesidad clara.
- Escribe comentarios para explicar decisiones o comportamientos que no sean evidentes.
- Mantén nombres, documentación y textos visibles coherentes con la identidad de **Ryx Isla**.
- No cambies identificadores técnicos, rutas o nombres de clases sin actualizar también todas sus referencias en el código.

## Reportar un error

Incluye la siguiente información, siempre que sea posible:

1. Modelo del dispositivo y versión de Android.
2. Versión de Ryx Isla o hash del commit.
3. Pasos para reproducir el problema.
4. Comportamiento esperado.
5. Comportamiento observado.
6. Registros relevantes, previamente revisados para eliminar datos privados.

No adjuntes notificaciones personales, conversaciones, números de teléfono, tokens, contraseñas ni datos de cuentas.

## Proponer una función

Explica qué problema resuelve la propuesta, cómo imaginas su funcionamiento y si afecta a permisos, privacidad, rendimiento o compatibilidad. Las propuestas pequeñas y bien delimitadas son más fáciles de evaluar.

## Licencia de las contribuciones

Ryx Isla se distribuye bajo **GNU General Public License v3.0 (GPL-3.0)**. Al enviar una contribución, aceptas que el aporte pueda distribuirse bajo esa misma licencia. Asegúrate de tener derecho a compartir el código, los recursos y cualquier otro material que aportes.
