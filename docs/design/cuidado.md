# Cuidado de la mascota

La mascota se cuida como un Tamagotchi dentro de la partida. El jugador la saca, las necesidades bajan mientras está con él, la mascota avisa, y una acción corta la recupera al momento. El diseño de entidad, estados y hooks está en [nucleo-mascota.md](nucleo-mascota.md).

El reloj de las necesidades depende de dónde está y de si el dueño está jugando. El detalle está en [gestion.md](gestion.md).

- En la caseta, las necesidades se quedan quietas.
- Fuera, con el dueño cerca, bajan al ritmo normal.
- Fuera, con el dueño lejos pero conectado, bajan a un ritmo lento. Otro jugador al lado no lo acelera.
- Al salir el dueño del servidor, las necesidades se congelan aunque la mascota se haya quedado fuera. Al volver, siguen donde estaban.

La muerte por abandono queda apagada. Si la salud llega a cero, la mascota queda debilitada hasta que se la cuide. Un servidor puede activar la muerte más adelante en la configuración.

## El ciclo

1. La mascota acompaña al jugador y sus necesidades bajan poco a poco.
2. Al cruzar un umbral avisa una sola vez, con comportamiento, sonido y un mensaje en la action bar.
3. El jugador la atiende en el mundo o desde la pantalla de cuidado.
4. La necesidad sube al instante y la mascota responde con corazones y su animación.
5. Si un aviso se ignora, pasa de molestia a enfermedad.

Qué tarda cada necesidad, cada cuánto se ensucia y cuánto aguanta en crítico antes de enfermar no forma parte del diseño fijo. Son opciones de configuración, con un valor de ejemplo para que un servidor pueda jugar sin tocar nada. El diseño fija las necesidades, los tramos, los avisos, las acciones y las etapas de la enfermedad.

## Necesidades

Cada una va de 0 a 100.

| Necesidad | Baja cuando | Qué se siente |
| --- | --- | --- |
| Hambre | Está fuera de la caseta | Pide comida |
| Ánimo | Está fuera de la caseta y no se juega con ella | Pide atención |
| Energía | Camina siguiendo al dueño. Se recupera mientras duerme | Se vuelve lenta y quiere echarse |
| Limpieza | Un suceso puntual, no un goteo constante. Cada suceso resta un trozo de golpe | Hay que limpiarla |
| Salud | Una necesidad lleva suficiente tiempo en crítico | Es la consecuencia de no atenderla |

Tramos:

- **Estable**, 60–100. Sigue con normalidad.
- **Bajo**, 25–59. Avisa una vez.
- **Crítico**, 0–24. Insiste y cambia de comportamiento.

Si varias están mal a la vez, expresa solo la más urgente: enferma, luego hambre, energía, limpieza y ánimo. El jugador ve un problema claro, no cuatro avisos juntos.

## Cómo se le nota

Funciona con una entidad vanilla. ModelEngine, si está, solo cambia la animación del estado. El plugin no depende de un icono ni de un modelo.

**Al cruzar a bajo.** Un sonido ambiente de su entidad, una partícula y una línea en la action bar: "Luna tiene hambre". No se repite hasta que esa necesidad se recupere y vuelva a bajar.

**En crítico.** Se sienta o se queda corta de paso, mira al jugador y repite un sonido suave de vez en cuando. Cada cuánto se repite ese sonido también es configuración. La action bar, mientras lo miras de cerca, dice la necesidad dominante.

**Contenta, justo después de atenderla.** Corazones, el sonido de comer o de jugar, y la animación del estado si hay modelo.

**Al mirarla** a unos pocos bloques, la action bar resume las necesidades. El nombre flotante sigue siendo solo su nombre.

Partículas de referencia:

- Hambre crítica: `ANGRY_VILLAGER`
- Sucia: `DUST_PLUME` sobre ella
- Enferma: `SNEEZE`
- Atendida: `HEART`
- Durmiendo: partículas de dormir cada pocos segundos

## Cómo la atiende el jugador

Clic derecho con la mano vacía abre la pantalla de cuidado. Con un objeto útil en la mano, el clic derecho hace la acción directa y no abre el menú.

| Acción | En el mundo | Efecto |
| --- | --- | --- |
| Alimentar | Clic derecho con una comida aceptada | Sube el hambre según el alimento. La comida favorita sube también un poco de ánimo |
| Jugar | Botón Jugar en la pantalla | Pasa a `PLAYING` un rato corto, sin objeto: salta, suenan notas y sube el ánimo. Gasta un poco de energía. La duración y cuánto sube el ánimo son configuración. Lanzar un juguete es la otra forma de jugar, en [juego.md](juego.md) |
| Acostar | Botón Acostar en la pantalla | Pasa a `SLEEPING`, deja de seguir y recupera energía. El hambre baja más despacio. Cuánto tarda en llenarse es configuración. Despierta sola al llenar la energía, o si el jugador la despierta |
| Limpiar | Clic derecho con un cepillo | Quita la suciedad y deja la limpieza llena |
| Curar | Clic derecho con la medicina, solo si está enferma | Empieza a recuperar salud |

La pantalla muestra el nombre, las cinco necesidades y los mismos botones. Alimentar y curar consumen el objeto del inventario. Jugar y acostar no consumen nada. Limpiar pide el cepillo en el inventario.

Si no puede hacer la acción, lo dice en la action bar: está enferma y no quiere jugar, o no tiene sueño, o eso no se lo come.

## Enfermedad

1. Una necesidad permanece en crítico el tiempo configurado. La mascota entra en **malestar**. Sigue al jugador más despacio, estornuda y la salud empieza a bajar.
2. Si en ese rato se corrige la causa (come, juega, duerme o se limpia), el malestar se pasa solo y la salud vuelve.
3. Si el crítico continúa otro tramo configurado, pasa a **enferma**. Rechaza el juego, se para a menudo y ya no se cura solo.
4. Enferma se cura con la medicina configurada (por defecto, frasco de miel). Después hace falta tener las demás necesidades fuera de crítico para que la salud termine de subir.
5. Salud a 0: **debilitada**. Se echa, no sigue y no juega. Se recupera con medicina, comida y descanso. No desaparece.

Un jugador que responde a los avisos no llega a verla enferma. La enfermedad es la escena de haberla dejado pasar, no el estado normal.

## Estados y órdenes del jugador

`FOLLOWING` y `SITTING` siguen siendo órdenes del jugador. `PLAYING` y `SLEEPING` son cuidados.

- Si la mandas seguir mientras duerme, despierta.
- Con la energía crítica se echa aunque no se lo pidas. Despertarla en ese momento baja un poco el ánimo.
- Enferma o debilitada obedece peor: camina más lenta y no entra en `PLAYING`.
- `SITTING` ordenado por el jugador se mantiene. Sentarse porque tiene hambre es un aviso, y al comer vuelve a seguir si esa era la orden.

## Vínculo

El vínculo es una sexta cifra de 0 a 100, distinta de las necesidades. Sube despacio mientras las necesidades se quedan en estable y ella está contigo. No cae por una comida tarde. Cae si permanece enferma o en crítico mucho rato.

Con el vínculo alto se pega más al caminar y suelta corazones de vez en cuando. Con el vínculo bajo se queda un paso más atrás. Es la sensación de haberla criado, sin evoluciones ni etapas todavía.

## Configuración

El ritmo no se define en el código. Todo intervalo, toda cantidad que sube o baja una necesidad y la duración de jugar o dormir salen de la configuración. Cambiarlos no cambia las reglas de arriba: las mismas necesidades, los mismos tramos, los mismos avisos y las mismas etapas.

Los números del ejemplo son un punto de partida cómodo para una sesión normal, en la que la mascota pide atención unas pocas veces. Un servidor puede alargarlos o acortarlos. Los alimentos y la medicina dependen del tipo de mascota. Los juguetes y el lanzamiento están en [juego.md](juego.md). El ejemplo amplía el de [nucleo-mascota.md](nucleo-mascota.md):

```yaml
care:
  hunger-minutes-to-critical: 30
  mood-minutes-to-critical: 30
  energy-minutes-to-critical: 40
  dirty-every-minutes: 20
  minutes-until-unwell: 3
  minutes-until-sick: 3
  decay-while-stored: false
  death-on-neglect: false

pets:
  wolf:
    entity: WOLF
    care:
      foods:
        BEEF: 35
        COOKED_BEEF: 55
      favorite: COOKED_BEEF
      medicine: HONEY_BOTTLE
    animations:
      FOLLOWING: walk
      SITTING: sit
      PLAYING: play
      SLEEPING: sleep
      SICK: sick
```

`SICK` es un estado visual más, por si el modelo tiene animación de enferma. Sin modelo basta con las partículas y el cambio de paso.
