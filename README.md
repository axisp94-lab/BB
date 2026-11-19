# AmethyysNPCs

Plugin Paper 1.21.11 / Java 21. BetterModel 2.2.0 est requis pour afficher les modèles. MythicMobs, ItemsAdder et Multiverse sont prévus comme intégrations optionnelles.

## Compilation
`mvn clean package` — le JAR est produit dans `target/AmethyysNPCs-0.1.0.jar`. Le workflow GitHub Actions compile le projet avec Java 21.

## Installation
1. Installer Paper 1.21.11 et Java 21.
2. Installer BetterModel 2.2.0.
3. Placer le JAR dans `plugins/` et démarrer le serveur.
4. Définir les NPC dans `plugins/AmethyysNPCs/npc.yml` ou utiliser `/anpc create`.

## Exemple npc.yml
```yaml
npcs:
  gardien:
    display-name: "&6Gardien"
    type: "limb:steve" # ou un nom de modèle BetterModel
    world: [world]
    x: 0.5
    y: 65
    z: 0.5
    yaw: 0
    pitch: 0
    skin: "file:gardiens.png"
```

`type: player` sélectionne le limb `steve`. Pour les modèles classiques, `type` est le nom BetterModel. `world` accepte un nom, une liste de noms ou `all`.

### Skins des NPC joueur
Le champ `skin` accepte :
- un UUID de profil Minecraft ;
- le nom d'un joueur actuellement connecté ;
- `file:nom.png`, pour un fichier placé directement dans `plugins/AmethyysNPCs/npcs/`.

Les PNG doivent mesurer 64×64 ou 64×32 pixels. Pour le modèle bras fins (slim), termine le nom par `_slim.png` ou `-slim.png`. Le serveur local de textures écoute uniquement sur l'interface de boucle locale et s'arrête avec le plugin.

## Scripts
Scripts : `plugins/AmethyysNPCs/scripts/<id>.aa`. Les événements et blocs utilisent une indentation de 4 espaces.

```python
on right click:
    say local "Bonjour !"
    play sound "entity.villager.yes"

on entity approach:
    say local "Bienvenue près de moi !"

on interval:
    if flag (ouvert) is on:
        say local "La porte est ouverte."
```

## Commandes disponibles
- `/anpc list`
- `/anpc create <id> <modele|limb:nom>`
- `/anpc tp <id>`
- `/anpc center <id>`
- `/anpc rename <id> <nom>`
- `/anpc kill <id>`
- `/anpc reload`

## État de cette livraison (0.1.0)
Le socle comprend le chargement de `npc.yml`, les NPC BetterModel basés sur `DummyTracker`, les hitboxes d'interaction, le suivi des joueurs visibles, le déplacement interpolé, les animations, les commandes de base, les premiers événements/conditions/actions et le stockage SQLite.

La stabilisation du cycle de vie, des commandes et du rechargement a été compilée avec succès par GitHub Actions. La prise en charge des skins PNG locaux vient d'être ajoutée ; elle reste à vérifier en jeu avec BetterModel 2.2.0.

Le langage `.aa` reste une première tranche : fonctions, boucles, tous les types d'événements, l'ensemble des variables/conditions/actions et les modes avancés de déplacement ne sont pas encore implémentés. Les adaptateurs MythicMobs, ItemsAdder et Multiverse restent à développer.
