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
    type: "limb:steve" # ou mythicmob:identifiant pour un mob MythicMobs
    world: [world]
    x: 0.5
    y: 65
    z: 0.5
    yaw: 0
    pitch: 0
    skin: "file:gardiens.png"
```

`type: player` sélectionne le limb `steve`. Pour les modèles classiques, `type` est le nom BetterModel. `type: mythicmob:gardien` crée une entité MythicMobs (MythicMobs doit être installé) ; son IA et sa gravité sont désactivées afin que le script contrôle son déplacement. `world` accepte un nom, une liste de noms ou `all`. Les NPC configurés dans des mondes Multiverse apparaissent et disparaissent avec le chargement/déchargement du monde.

### Skins des NPC joueur
Le champ `skin` accepte :
- un UUID de profil Minecraft ;
- le nom d'un joueur actuellement connecté ;
- `file:nom.png`, pour un fichier placé directement dans `plugins/AmethyysNPCs/npcs/`.

Les PNG doivent mesurer 64×64 ou 64×32 pixels. Pour le modèle bras fins (slim), termine le nom par `_slim.png` ou `-slim.png`. Le serveur local de textures écoute uniquement sur l'interface de boucle locale et s'arrête avec le plugin.

## Scripts .aa
Les scripts se trouvent dans `plugins/AmethyysNPCs/scripts/<id>.aa`. Les événements et blocs utilisent une indentation de quatre espaces.

```python
def message_accueil(joueur):
    return "Bienvenue sur le serveur !"

on right click:
    set "message" message_accueil(event entity)
    say local message

on entity approach:
    loop all players:
        send to loop-player "Un NPC est proche de vous."

on npc approach:
    set "autre_npc" name of event npc
    say local "Bonjour, autre_npc !"

on npc leave:
    say local "À bientôt !"

on interval:
    if flag (ouvert) is on:
        say local "La porte est ouverte."

    set state (guard)
    add state (alert)
    if state is (guard):
        say local "Je monte la garde."
    if has state (alert):
        say local "Je suis en alerte."

    loop 3 times:
        say local "Rappel numéro loop-number"

    setblock (100,64,100) (stone)
    # Modification réelle uniquement sur demande explicite :
    # setblock real (100,64,100) (stone)
```

Éléments pris en charge dans cette version :
- événements de clic, d'approche des joueurs, de proximité et de départ entre NPC, d'intervalle et de spawn ;
- blocs `if`, variables locales/partagées/globales et fonctions avec paramètres et `return` ;
- boucles `loop all players`, `loop all npcs`, `loop all (liste)` et `loop N times`, avec `stop loop` et `skip loop` ;
- sélecteurs `@p`, `@a`, `@n`, `@e[type=...]`, `player:<pseudo>` et `npc:<id>` pour les cibles compatibles ;
- actions de message, son vanilla ou identifiant custom `namespace:sound` (ItemsAdder), animation BetterModel, déplacement, téléportation, suivi, maintien de distance, déplacement aérien direct, états, simulation de blocs et commande console.

Les états principaux et secondaires sont conservés en mémoire et réinitialisés au redémarrage du serveur. `setblock (position) (bloc)` ne modifie pas le monde : il joue seulement les particules et le son. Le préfixe explicite `setblock real` applique réellement le changement. Les fonctions sont synchrones : `wait` n'est pas accepté dans une fonction qui retourne une valeur. Les sélecteurs d'entités sont limités au rayon défini par `selector-range` (64 blocs par défaut). Les listes sauvegardées sont actuellement interprétées depuis une chaîne séparée par des virgules.

### Déplacements
- `move to <cible>` utilise A* sur les blocs praticables et des checkpoints.
- `tp <cible>` téléporte immédiatement le NPC.
- `fly to <cible>` effectue un déplacement direct en trois dimensions.
- `follow <cible>`, `keep distance <cible> <distance>` et `stop follow` contrôlent le suivi.

Le pathfinding est réglable dans `config.yml` : activation, nombre maximal de nœuds, distance maximale, vitesse et espacement des checkpoints. Il ne recherche que dans les chunks déjà chargés.

## Commandes disponibles
- `/anpc list`
- `/anpc create <id> <modele|limb:nom>`
- `/anpc tp <id>`
- `/anpc center <id>`
- `/anpc rename <id> <nom>`
- `/anpc kill <id>`
- `/anpc reload`

## État de cette livraison (0.1.0)
Le socle comprend le chargement de `npc.yml`, les NPC BetterModel basés sur `DummyTracker`, les hitboxes d'interaction, le suivi des joueurs visibles, les commandes de base, les événements/conditions/actions de première tranche et le stockage SQLite.

La stabilisation du cycle de vie, des commandes et du rechargement a été compilée avec succès par GitHub Actions. La prise en charge des skins PNG locaux, la correction du parseur, les variables, fonctions, boucles, sélecteurs, le pathfinding, les déplacements aériens directs et le suivi ont également été compilés. Les essais en jeu avec Paper 1.21.11 et BetterModel 2.2.0 restent à effectuer.

Les interactions NPC-NPC de proximité et les événements `on npc approach` / `on npc leave` sont disponibles sans mécanisme de combat. Les types `mythicmob:<id>`, les sons custom namespacés et le chargement dynamique des mondes sont ajoutés ; leur validation en jeu reste nécessaire.
