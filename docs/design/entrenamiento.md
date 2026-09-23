# Entrenamiento

La mascota aprende trucos con paciencia. La palabra la elige el dueño. El significado se muestra una sola vez. Las repeticiones, premiadas solo cuando lo hace bien, fijan el truco. El cuidado está en [cuidado.md](cuidado.md) y la entidad en [nucleo-mascota.md](nucleo-mascota.md).

Un truco funciona con una entidad vanilla. ModelEngine, si el tipo tiene animación para ese truco, la reproduce en lugar de la pose genérica. La action bar confirma la reacción también cuando la pose ya se ve.

## Qué es un truco

Una reacción definida por el plugin: pose, movimiento, sonido y, cuando el cuerpo no puede enseñarlo, una línea en la action bar. No es una animación ni una skill de MythicMobs.

| Truco | En cualquier mob | Pose vanilla cuando existe |
| --- | --- | --- |
| Sentarse | Se para y deja de seguir. Usa el estado `SITTING` | Lobo, gato, loro y zorro se sientan |
| Venir | Camina hasta el dueño | |
| Quieto | Se queda donde está | |
| Hablar | Reproduce su sonido ambiente | |
| Saltar | Da un salto | |
| Girar | Da una vuelta sobre sí | |
| Pedir | Levanta la mirada | El lobo usa la cabeza interesada. El gato, la cabeza alta |
| Dar la pata | Mira al dueño, suelta una partícula y la action bar dice que da la pata | Con modelo, la animación de la pata |

Sentarla o acostarla desde el cuidado sigue siendo una orden directa. Obedecer la palabra es el truco, y solo existe cuando ya lo ha aprendido.

Cada mascota guarda sus propias palabras. El lobo de un jugador puede usar `sit` y el de otro `siéntate` para el mismo truco. Una palabra corresponde a un solo truco.

## Cómo se le enseña

1. Con una golosina en la mano, clic derecho sobre la mascota. La comida favorita sirve de golosina. Entra en entrenamiento: mira al dueño y deja de vagar.
2. El dueño la mira y escribe en el chat la orden entera: `sit`, `siéntate` o `dale la pata`. El mensaje sigue viéndose en el chat. El plugin lo escucha solo si el jugador está cerca y mira a su mascota.
3. Si la palabra es nueva, la mascota inclina la cabeza y aparece una fila de botones: Sentarse, Venir, Quieto, Hablar, Saltar, Girar, Pedir y Dar la pata. Un clic ata esa palabra a ese truco. No vuelve a preguntarse. Agacharse, saltar o girar es lo que hace ella al obedecer, no lo que hace el dueño para enseñárselo.
4. Luego se repite la palabra. Al principio no entiende, o lo hace a medias (se sienta y se levanta enseguida). Si lo hace bien, hay un momento corto para premiarla con la golosina. Premiar un fallo casi no hace avanzar. Un acierto sin premio no se fija.
5. El truco pasa de no entender a obedecer a veces y, al final, a obedecer. El ánimo alto y el vínculo aceleran un poco el avance.

Hambre crítica, enfermedad o agotamiento impiden la sesión: no presta atención. Tras varios intentos se aburre y la sesión termina. También termina si el dueño se aleja o guarda la golosina.

Cuántos intentos aguanta, cuánto avanza cada premio y cuánto dura la ventana para premiar son configuración. No forman parte de las reglas de arriba.

## Cómo se le ordena

Con el truco aprendido, el dueño mira a la mascota y escribe solo esa palabra. La línea de chat es la orden completa, así `sit` no se dispara dentro de otra frase. Obedece la mascota mirada. Si el jugador no mira a ninguna, ninguna obedece.

Fuera del entrenamiento, una palabra desconocida no provoca reacción y el mensaje de chat sigue su curso.

## Qué queda fuera

No hay conversación libre con la mascota. No aprende trucos que el servidor no tenga definidos. MythicMobs no participa en la orden: sus skills siguen siendo del mob, y el truco es de este plugin.
