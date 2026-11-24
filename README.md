# AmethyysNPCs

Plugin Paper 1.21.11 / Java 21. BetterModel 2.2.0 est requis pour afficher les modèles. MythicMobs, ItemsAdder et Multiverse sont pris en charge comme intégrations optionnelles.

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

`type: player` sélectionne le limb `steve`. Pour les modèles classiques, `type` est le nom BetterModel. `type: mythicmob:gardien` crée une entité MythicMobs (MythicMobs doit être installé) ; son IA et sa gravité sont désactivées afin que le script contrôle son déplacement. Les NPC ne subissent pas les dégâts du jeu, mais l'événement `on damage` et la condition `npc is under attack` restent déclenchés lorsqu'une attaque ou une autre source de dégâts les touche. `world` accepte un nom, une liste de noms ou `all`. Les NPC configurés dans des mondes Multiverse apparaissent et disparaissent avec le chargement/déchargement du monde.

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

on offhand item click:
    if type of event offhand item == DIAMOND:
        say local "Un diamant dans ta main secondaire !"
    else:
        say local "Tu utilises un objet dans ta main secondaire."

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
    if state is (guard):
        say local "Je monte la garde."
    else if has state (alert):
        say local "Je suis en alerte."
    else:
        say local "Tout est calme."

    if player is sneaking and player has permission "anpc.vip":
        say local "Accès VIP détecté."

    loop 3 times:
        say local "Rappel numéro loop-number"

    setblock (100,64,100) (stone)
    # Modification réelle uniquement sur demande explicite :
    # setblock real (100,64,100) (stone)
```

Éléments pris en charge dans cette version :
- événements de clic droit/gauche, de clic avec un objet en main principale (`item click`) ou en main secondaire (`offhand item click`), de dégâts (joueurs, projectiles, mobs et environnement), d'approche/de départ des joueurs et des NPC, d'intervalle et de spawn ; les événements de spawn et d'intervalle suivent aussi les chargements/déchargements de mondes ;
- blocs `if` / `else if` / `else`, conditions combinables avec `and`, `or`, `not` et parenthèses, variables locales/partagées/globales persistantes et variables `player` persistantes par UUID (les valeurs existantes ne sont pas réinitialisées lors du spawn ou d'un reload), et fonctions avec paramètres et `return` ;
- boucles `loop all players`, `loop all npcs`, `loop all (liste)` et `loop N times`, avec `stop loop` et `skip loop` ; `loop all npcs` parcourt chaque instance chargée, y compris les copies dans plusieurs mondes, avec `name of loop-npc` / `location of loop-npc` ; `name of loop-player` / `location of loop-player` sont aussi disponibles ;
- sélecteurs `@p`, `@a`, `@n`, `@e[type=...]`, `player:<pseudo>` et `npc:<id>` pour les cibles compatibles ;
- valeurs `event item` / `event offhand item` et leurs types avec `type of event item` / `type of event offhand item` ;
- actions de message, son vanilla ou identifiant custom `namespace:sound` (ItemsAdder), animation BetterModel, déplacement, téléportation, suivi, maintien de distance, déplacement aérien direct, états, simulation de blocs et commande console.

Les états principaux et secondaires sont sauvegardés dans SQLite et conservés après un redémarrage du serveur. La condition `npc is under attack` devient vraie après un dégât reçu par le NPC (attaque directe, projectile ou autre source) et reste active pendant `script-under-attack-duration-ticks` ticks (100 par défaut, soit 5 secondes) après le dernier dégât. `setblock (position) (bloc)` ne modifie pas le monde : il joue seulement les particules et le son. Le préfixe explicite `setblock real` applique réellement le changement. Le bloc `cooldown 10 seconds:` limite la fréquence d'exécution de son contenu. Les fonctions sont synchrones : `wait` n'est pas accepté dans une fonction qui retourne une valeur. Les sélecteurs d'entités sont limités au rayon défini par `selector-range` (64 blocs par défaut). Les listes sauvegardées sont actuellement interprétées depuis une chaîne séparée par des virgules.

### Déplacements
- `move to <cible>` utilise A* sur les blocs praticables et des checkpoints.
- `tp <cible>` téléporte immédiatement le NPC dans son monde actuel. Les instances d'un même NPC configurées dans plusieurs mondes restent indépendantes ; un script ne peut pas déplacer une instance vers un autre monde.
- `fly to <cible>` calcule un trajet tridimensionnel en évitant les blocs non passables.
- `climb to <cible>` suit une paroi solide continue en montant ou descendant.
- `swim to <cible>` calcule un trajet dans un volume d'eau.
- `amphibious to <cible>` combine sol praticable, parois escaladables et eau.
- `follow @p at 2` maintient environ deux blocs ; `follow @p min 2 max 5` maintient une plage ; `keep distance 4 from @p` conserve une distance minimale ; `stop follow` arrête le suivi.

Le pathfinding terrestre, aérien et spécial est réglable dans `config.yml` : activation terrestre, nombre maximal de nœuds, distance maximale, vitesse et espacement des checkpoints. Les modes d'escalade et de nage exigent respectivement une paroi solide contiguë et deux blocs d'eau libres en hauteur. Le mode amphibie combine ces surfaces et les sols praticables. Le calcul ne recherche que dans les chunks déjà chargés.

### Variables par joueur

Une variable déclarée avec la portée `player` conserve une valeur différente pour chaque joueur, même après une reconnexion ou un redémarrage :

```python
on right click:
    create variable ("visites", 0, player)
    set "visites" (visites + 1)
    say local "Tu m'as parlé visites fois."
```

La valeur est stockée dans SQLite sous l'UUID du joueur. Les variables de portée `player` doivent être lues ou modifiées depuis un événement associé à un joueur. Les expressions numériques acceptent `+`, `-`, `*`, `/`, `%` et les parenthèses.

## Commandes disponibles
- `/anpc list`
- `/anpc create <id> <modele|limb:nom|mythicmob:id>`
- `/anpc tp <id>`
- `/anpc center <id>`
- `/anpc rename <id> <nom>`
- `/anpc kill <id>`
- `/anpc reload`

`/anpc list` affiche toutes les définitions de `npc.yml`, y compris celles dont le monde n'est pas chargé, avec leur statut. Les commandes de renommage, suppression et centrage fonctionnent également sur ces définitions non chargées.

## État de cette livraison (0.1.0)
Le socle comprend le chargement de `npc.yml`, les NPC BetterModel basés sur `DummyTracker`, les hitboxes d'interaction, le suivi des joueurs visibles, les commandes de base, les événements/conditions/actions de première tranche et le stockage SQLite.

La stabilisation du cycle de vie, des commandes et du rechargement a été compilée avec succès par GitHub Actions. La prise en charge des skins PNG locaux, la correction du parseur, les variables, fonctions, boucles, sélecteurs, le pathfinding terrestre et aérien, les déplacements de suivi et le maintien de distance ont également été compilés. Les essais en jeu avec Paper 1.21.11 et BetterModel 2.2.0 restent à effectuer.

Les interactions NPC-NPC de proximité et les événements `on npc approach` / `on npc leave` sont disponibles sans mécanisme de combat. Les types `mythicmob:<id>`, les sons custom namespacés et le chargement dynamique des mondes sont ajoutés ; leur validation en jeu reste nécessaire.
