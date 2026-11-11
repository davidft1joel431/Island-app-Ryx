# Actualizaciones LAN para la APK debug

El actualizador solo se compila en la variante `debug`. Busca una versión nueva cada 30 segundos mientras la sección **Actualizaciones y descargas** está abierta, y también permite buscar manualmente. La variante Release no incluye el host local, el permiso para instalar paquetes ni el proveedor de archivos del actualizador.

## Preparar el PC

1. Compila e instala una vez `app-debug.apk` en el teléfono.
2. En el PC, desde la raíz del repositorio, ejecuta:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   py -3 scripts/serve-debug-apk.py
   ```

3. Permite Python en el Firewall de Windows para la red privada si lo solicita.
4. Mantén el PC y el teléfono en la misma red Wi-Fi. El servidor escucha en el puerto TCP 8765; la APK intenta primero `192.168.100.6` y después `172.22.160.1`. Si el PC tiene otra IP en esa red, cámbiala en el campo **IP o nombre del PC** de la APK debug. El servidor escucha en todas las interfaces del PC.
5. Al compilar una versión debug con un `versionCode` mayor, el teléfono detectará la APK y ofrecerá descargarla. Android abrirá su instalador y pedirá la confirmación del usuario.

El servidor entrega la APK que exista actualmente en `app/build/outputs/apk/debug/app-debug.apk`; vuelve a compilar antes de probar cambios. El proceso de Python debe seguir abierto durante la descarga.

## Marcar una versión lista para GitHub

Al arrancar, el servidor imprime una clave temporal. Introdúcela en el campo **Clave temporal del servidor** de la APK debug. Después de instalar y probar la versión actual, pulsa **vX.Y.Z lista para lanzar** y confirma el envío.

La orden se envía por `POST /publish` con la versión instalada y la clave en el encabezado `X-Ryx-Token`. El PC valida que la rama sea `main`, esté sincronizada con `origin/main`, que la versión coincida y que los cambios estén en rutas del proyecto permitidas. Luego vuelve a compilar Debug, crea el commit y la etiqueta `vX.Y.Z`, y hace push de `main` y de la etiqueta. GitHub Actions compila y publica la APK oficial Release; debe tener configurados los secretos de firma de [RELEASE_SIGNING.md](RELEASE_SIGNING.md).

El proceso falla sin hacer commit si detecta archivos fuera de las rutas permitidas, una rama desincronizada, una etiqueta existente o errores de compilación. Git debe estar autenticado en el PC mediante Git Credential Manager. La clave cambia al reiniciar el servidor; no la publiques ni la incluyas en el repositorio.
