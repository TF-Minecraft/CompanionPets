# Companion ModelEngine blueprints

These editable Chihuahua and Yorkshire blueprints include the confirmed
Blockbench corrections from 2026-10-10. Each has eleven original animations and
an embedded 80 × 112 PNG texture with a 40 × 56 UV canvas. Both texture dimensions
are multiples of 16, so these textures do not limit Minecraft's level-four
mipmaps. The Yorkshire also includes the corrected eyebrow geometry.

Copy the `.bbmodel` files into `plugins/ModelEngine/blueprints/`, run
`meg reload models`, then rebuild the combined ItemsAdder pack with `iazip`.
Apply the rebuilt pack on the client. These asset updates do not require a
CompanionPets JAR update or a server restart.

The complete generated resources and their checksums are maintained in the
ServerAssets repository under `models/modelengine/companions/` and
`configs/ModelEngine/resource pack/`.
