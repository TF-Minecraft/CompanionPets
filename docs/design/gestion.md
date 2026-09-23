# Gestión de las mascotas

Cada mascota es un individuo: nombre, sexo, tipo, necesidades, trucos y juguete favorito. El mismo tipo puede repetirse. Cuatro golden retriever son cuatro mascotas, cada una con su nombre. El cuidado de las necesidades está en [cuidado.md](cuidado.md).

## Dónde puede estar

Hay dos cupos, los dos de configuración. Un ejemplo de partida es 20 en la caseta y 4 fuera. Fuera cuenta igual si te sigue o si se quedó en un sitio.

| Dónde | Qué se ve | Necesidades |
| --- | --- | --- |
| Caseta | No está en el mundo | Quietas, esté el dueño conectado o no, esté cerca o lejos |
| Contigo | Te sigue | Ritmo normal |
| Dejada | Se queda donde la sentaste | Ritmo normal si el dueño está cerca. Ritmo lento si está lejos y sigue conectado |

El ritmo normal y el lento son configuración. La distancia que separa cerca de lejos es `owner-near-radius`: vale para el ritmo y para el llanto. Otro jugador al lado no devuelve el ritmo normal: ese ritmo es que el dueño está ahí para atenderla.

Al salir del servidor, las necesidades se congelan aunque se haya quedado fuera. Al volver, sigue en el mismo sitio y con las mismas necesidades. Si el dueño aparece lejos de ella, el ritmo lento vuelve a contar. Olvidarse de guardarla se nota al entrar: sigue fuera, ocupa un hueco y está donde la dejó. No enferma por el tiempo que el dueño pasó desconectado.

La muerte por abandono sigue apagada. Como mucho llega a debilitada, como en el cuidado.

## Caseta y silbato

La caseta es un bloque. Al usarla se abre la lista: nombre, tipo, sexo, un resumen de las necesidades y si está fuera o guardada. Desde ahí se saca, se guarda o se le cambia el nombre.

El silbato abre el mismo menú en cualquier lugar. Sirve para guardar una que se quedó lejos o para llamarla, sin volver andando. Llamarla carga su chunk y teletransporta a esa misma entidad.

## Huevo

El objeto que hace de huevo sale de la configuración de cada tipo. El crafteo y el permiso para craftearlo los resuelve el sistema de profesiones, fuera de este plugin.

Al usarlo, el chat pide el nombre. Cuando se confirma, el huevo se gasta y la mascota aparece al lado del jugador, dentro del cupo de las que están fuera. Si ese cupo está lleno, el huevo no se gasta y el chat lo dice. Si el nombre no llega a confirmarse, el huevo tampoco se gasta.

El sexo se sortea al nacer y se anuncia en ese momento. Si la configuración del tipo es `choose`, el chat lo pregunta después del nombre. Es identidad: no cambia el cuidado ni los trucos.

## Quién puede atenderla

El dueño la entrena, la nombra, la guarda y la saca. Cualquier otro jugador puede darle los cuidados básicos si la tiene delante: alimentar, limpiar y curar. Jugar, los trucos y el silbato siguen siendo del dueño.

## Llanto

Si está fuera y el dueño está conectado pero lejos, llora de vez en cuando: un sonido suave y una partícula. El intervalo es configuración. Para cuando el dueño vuelve cerca, o justo después de que alguien la atienda. En la caseta no llora. Con el chunk descargado tampoco se oye, porque el mob no está en memoria. Al cargar el chunk, si el dueño sigue lejos, el llanto vuelve con él.

## El cuerpo lo guarda el chunk

Una mascota que está fuera se comporta como un lobo domesticado. El chunk la escribe en disco al descargarse y la vuelve a crear al cargarse, con el mismo UUID, la posición y si estaba sentada o siguiendo. El plugin no la borra al descargar el chunk ni la vuelve a spawnear cuando hay un jugador cerca. Ese ciclo duplicaría el mob: uno lo traería el archivo del chunk y otro el plugin.

La ficha del plugin guarda lo que Minecraft no sabe: id de la mascota, dueño, tipo, nombre, sexo, necesidades, trucos, juguete favorito y si está en la caseta o fuera. Ese id se escribe también en los datos persistentes de la entidad. Al cargar el chunk, la entidad ya está; el plugin la reconoce por ese id y reengancha el comportamiento. El UUID de la entidad identifica el cuerpo mientras existe. El id de la mascota es el que no cambia, también si al sacarla de la caseta aparece un cuerpo nuevo.

Mientras el cuerpo existe, la ficha apunta de vez en cuando su mundo y su posición. Sirve para que el silbato encuentre el chunk y teletransporte a esa misma entidad. No sirve para crear otra.

El reloj de las necesidades no depende de que el mob esté en memoria. Sigue la tabla de arriba: quieto en la caseta, normal con el dueño cerca, lento si está lejos y conectado, y parado si el dueño se desconecta. Un chunk descargado no piensa, pero la ficha sí actualiza las necesidades.

La caseta es la única vez que el plugin quita la entidad del mundo. Al guardarla se borra, para que el chunk no la conserve. Al sacarla se crea una vez, persiste, y a partir de ahí la guarda el chunk.

El UUID se conserva porque el chunk guarda la entidad. Eso lo decide `setPersistent(true)`, que ya es el valor por defecto: no hace falta tocarlo salvo que algo lo hubiera puesto en false. `setTamed(true)` no interviene en el UUID. Solo marca dueño y la pose de domesticado en un lobo, un gato o un loro. Un mob que Minecraft borraría al alejarse, como muchos de MythicMobs que no son animales, lleva además `setRemoveWhenFarAway(false)`, para que no desaparezca antes de que el chunk lo escriba.

## El modelo sin MythicMobs

MythicMobs no hace falta para tener modelo. Si el servidor tiene ModelEngine y el tipo declara un blueprint, este plugin pone el modelo al crear el cuerpo: al usar el huevo y al sacarla de la caseta. Crea el `ModeledEntity`, añade el blueprint y deja `setSaved(true)`.

Ese guardado escribe en la entidad el id del blueprint. Minecraft lo mete en el chunk junto con el mob. Al cargar el chunk, ModelEngine lee ese id y monta el modelo otra vez. No mira el tipo de entidad y no espera a MythicMobs.

El id del blueprint sigue en la configuración del tipo. Si la entidad carga y el modelo no está, el plugin lo aplica de nuevo, sobre el mismo cuerpo.

Si el tipo es un MythicMob y ese mob ya pone el modelo con `model{save=true}`, el plugin no añade otro. Busca el `ModeledEntity` que ya existe y solo le pide la animación del estado.

Sin ModelEngine no hay modelo: se ve la entidad vanilla, y el resto del plugin funciona igual.

## Configuración

Los cupos, la distancia a la que el dueño cuenta como cerca y el ritmo lento son un punto de partida.

```yaml
limits:
  max-stored: 20
  max-out: 4

presence:
  owner-near-radius: 32
  away-rate: 0.25

pets:
  wolf:
    entity: WOLF
    egg: WOLF_SPAWN_EGG
    sex: random
    model: wolf_custom
```

`sex` puede ser `random` o `choose`. `away-rate` es la fracción del ritmo normal mientras el dueño está lejos y conectado. No hay radio de aparición: el chunk carga el mob cuando le toca a Minecraft. `model` es el id del blueprint de ModelEngine. Si ModelEngine no está instalado, se ignora. `owner-near-radius` es la distancia a partir de la cual el dueño cuenta como lejos.
