# Juego y juguetes

Jugar tiene dos formas. El botón Jugar de la pantalla de cuidado es un juego corto, sin objeto. Lanzar un juguete es la forma larga: ella lo persigue, lo trae y lo suelta delante del dueño. El ánimo y la energía son las necesidades de [cuidado.md](cuidado.md). Recoger el juguete es jugar, no un truco de [entrenamiento.md](entrenamiento.md).

## Qué es un juguete

Un juguete es un objeto de la lista del tipo de mascota. Un lobo puede tener el palo y la pluma. Lo que no está en esa lista no se lanza y ella no lo persigue, aunque se caiga al suelo.

El objeto no tiene que ser un proyectil de Minecraft. El plugin aparta uno de la mano y lo dispara como una bola de nieve con la apariencia del juguete. Se ve el palo en el aire. Al tocar el suelo o a una criatura, la bola desaparece y queda el objeto. El tiro no hace daño.

Ese objeto es el mismo que salió de la mano. No se duplica. Otro jugador no puede quedárselo mientras dura el juego: solo lo recoge la mascota. Ella ignora los objetos del suelo que no son ese lanzamiento.

## Cómo se lanza

Clic derecho al aire, con un juguete de la lista en la mano y la mascota invocada.

La trayectoria, la gravedad y el choque los resuelve la bola de nieve. El plugin decide que ese objeto es un juguete, elige la velocidad y la recorta entre un mínimo y un máximo. Esos límites viven en la configuración: un tiro a plena fuerza se queda en la zona.

La fuerza, dentro de esos límites, sale de cómo se apunta y de si el jugador se agacha:

- Hacia delante el tiro cae cerca. Un poco hacia arriba llega más lejos. En vertical cae al lado.
- Sin agacharse sale a la velocidad alta. Agachado, a la baja.

Un palo no se tensa como un arco. El cliente solo mantiene el clic en objetos que ya se usan así, y un arco, un tridente o una comida arrastrarían su propio efecto. Por eso el gesto es apuntar y, si se quiere un tiro corto, agacharse.

Un segundo lanzamiento cancela el primero. El primer objeto se suelta delante del dueño y empieza el nuevo tiro.

## El recorrido

1. El juguete sale. La mascota pasa a `PLAYING` y corre a por él.
2. Lo recoge del suelo. En la vuelta no hace falta mostrarlo encima: basta con que deje de estar en el suelo y ella vuelva.
3. Se acerca y lo suelta en el suelo, delante del dueño. No entra en la mano ni en el inventario.
4. Vuelve a la orden que tenía, seguir o sentada. Sube el ánimo y gasta un poco de energía. Cuánto sube y cuánto gasta es configuración, como el resto del cuidado.

Llevar el objeto en la cabeza durante la vuelta es un detalle visual aparte, para más adelante. No forma parte de este recorrido. Si se añade, en un mob que ya use el casco por MythicMobs ese casco no se toca.

Si el tiro no se puede completar y el dueño sigue ahí, el objeto también se suelta delante. Si en ese momento no está, queda guardado con la mascota y se suelta delante la próxima vez que estén juntos.

## El favorito

Al crear la mascota se sortea uno de los juguetes de su tipo. Lanzar más veces otro no lo cambia. Dos mascotas del mismo tipo pueden preferir objetos distintos.

En la pantalla de cuidado se muestra desde el principio. Cuando se lanza ese, corre más, suelta más corazones y el ánimo sube más. Los demás de la lista también los trae, con una reacción más tranquila.

Si la lista de la configuración cambia:

- El suyo sigue en la lista: se queda con él.
- Se añaden juguetes: no se vuelve a sortear.
- Quitan el suyo y quedan otros: se sortea otro entre los que siguen, y se avisa una vez.
- La lista queda vacía: no tiene favorito y no hay lanzamiento. Cuando vuelva a haber juguetes, se sortea.

## Qué queda fuera

Cualquier objeto del inventario como juguete. Proyectiles de verdad, como perlas, tridentes o pociones. Un favorito escrito por el administrador para cada mascota. Distinguir el mismo objeto por nombre o encantamiento. Una bolsa de juguetes en la mascota. Enseñarle a buscarlo como un truco. Varias mascotas corriendo al mismo objeto: lo busca la que está invocada con el dueño.

## Configuración

La lista de juguetes es del tipo. El favorito de cada mascota no se escribe aquí. Las velocidades son un punto de partida, no una regla fija.

```yaml
play:
  throw-speed-low: 0.6
  throw-speed-high: 1.1

pets:
  wolf:
    entity: WOLF
    toys:
      - STICK
      - FEATHER
```
