# AmethyysNPCs

Plugin Paper 1.21.11 / Java 21. BetterModel 2.2.0 est requis pour afficher les modèles. MythicMobs, ItemsAdder et Multiverse sont détectés comme intégrations optionnelles ; leurs adaptateurs complets restent à développer.

## Compilation

`mvn clean package` — le JAR ombré est produit dans `target/AmethyysNPCs-0.1.0.jar`.

Le workflow GitHub Actions compile le projet avec Java 21 et Maven à chaque modification de la branche.

## Installation

1. Installer Paper 1.21.11 et Java 21.
2. Installer BetterModel 2.2.0.
3. Placer le JAR dans `plugins/` et démarrer le serveur.
4. Définir les NPC dans `plugins/AmethyysNPCs/npc.yml` ou utiliser `/anpc create`.

## Définition des NPC

```yaml
npcs:
  gardien:
    display-name: "&6Gardien"
    type: "limb:steve"
    world: [world]
    x: 0.5
    y: 65
    z: 0.5
    yaw: 0
    pitch: 0
    skin: "file:skins/gardien.png"
```

- `type: player` sélectionne le limb `steve`.
- `type: limb:nom` utilise un limb BetterModel.
- Pour un modèle classique, `type` correspond au nom du modèle BetterModel.
- `skin` accepte un UUID Minecraft, un pseudo (si son UUID est connu) ou `file:skins/nom.png`. Les skins locaux doivent être des PNG 64x64 ou 64x32 dans `plugins/AmethyysNPCs/skins/`.
- `world` accepte un nom, une liste de noms ou `all`. Une instance est créée dans chaque monde ciblé.

## Scripts .aa

Les scripts sont placés dans `plugins/AmethyysNPCs/scripts/<id>.aa`. Le nom du fichier doit correspondre à l'ID du NPC. Les blocs utilisent une indentation de 4 espaces.

### Événements et actions de base

```python
on right click:
    say local "Bonjour !"
    play sound "entity.villager.yes"
    play sound "itemsadder:amethyys:welcome"
    emote "wave"
    stop animation

on entity approach:
    say local "Bienvenue près de moi !"

on interval:
    if flag (ouvert) is on:
        say local "La porte est ouverte."

on player chat:
    if event message contains "bonjour":
        say local "Bonjour !"

on npc approach:
    say local "Un autre NPC approche."
```

### Variables

- `local` : propre à une instance NPC dans un monde.
- `global` : commune à tous les NPC.
- `shared` : commune uniquement aux NPC listés dans la déclaration.

```python
on right click:
    create variable compteur (0) local
    create variable serveur (1) global
    create variable stock (0) shared [marchand, gardien]
    set "compteur" (compteur + 1)
    flag on (ouvert)
    flag off (ouvert)
```

Les variables sont conservées dans SQLite. Les variables locales sont isolées par NPC et par monde. Les variables globales et partagées restent communes selon leur portée.

### Fonctions globales

Les fonctions déclarées dans un fichier `.aa` sont accessibles depuis les scripts de tous les NPC. Les paramètres sont locaux à l'appel et `return` renvoie une valeur.

```python
function saluer(nom):
    return "Bonjour " + nom

on right click:
    set "message" (call saluer("visiteur"))
    say local message
```

### Boucles

```python
on interval:
    loop all players:
        send to loop-player "Un gardien vous observe."

    loop all npcs:
        play anime "idle"

    loop 5 times:
        say local "Patrouille"

    loop all (liste):
        say local loop-value
        if loop-number == 3:
            skip loop
```

`stop loop` arrête la boucle courante ; `skip loop` passe à l'itération suivante.

### Déplacements et suivi

```python
on right click:
    set checkpoint "entrée"
    move to checkpoint "entrée"
    move to @p
    stop movement
    fly to @p
    follow @p at 2
    # ou : follow @p min 2 max 5
    # ou : keep distance 4 from @p

on left click:
    stop follow
```

Le déplacement au sol utilise un pathfinding avec évitement des blocs solides. `fly to` utilise un chemin 3D avec évitement des blocs et une animation `fly` si le modèle la fournit. La limite est réglable dans `config.yml` via `movement.max-path-attempts` (100 par défaut). Les checkpoints manuels se définissent avec `set checkpoint "nom"`, se rejoignent avec `move to checkpoint "nom"` et se retirent avec `remove checkpoint "nom"`. Utilise `stop movement` pour interrompre le déplacement et le suivi en cours. Le suivi s'arrête aussi si la cible se déconnecte ou change de monde.

### Blocs : simulation et modification explicite

Les actions de simulation n'altèrent jamais les blocs du monde. Elles affichent uniquement les particules et sons correspondants.

```python
on walk:
    if block below is stone:
        simulate break block at (0, 64, 0)
        simulate place stone at (1, 64, 0)
        simulate interact with block at (2, 64, 0)
```

Pour modifier réellement le monde, le script doit employer explicitement `set block <matériau> at (x, y, z)` ou `break block at (x, y, z)`.

### Combat et hostilité

L'hostilité est désactivée par défaut. Un script doit l'activer explicitement. Les sélecteurs de cible peuvent être combinés avec des virgules.

```python
on right click:
    target all players, monsters
    set hostile on

on left click:
    stop attack
```

`attack @p` choisit le joueur le plus proche et active l'hostilité. Les sélecteurs pris en charge comprennent `all players`, `all mobs`, `all entities`, `monsters`, `animals`, `player:<pseudo>`, `mob:<type>`, `entity:<type>` et `mythic:<id>`. Les portées, dégâts et délais sont réglables dans `config.yml` sous `combat`.

### Surveillance des scripts

Si un script continue à s'exécuter pendant 100 ticks sans modifier d'état, les joueurs possédant la permission `anpc.script.warning` reçoivent un avertissement. La permission et le contrôle sont réglables dans `config.yml`.

### Cooldowns

Les cooldowns sont indépendants pour chaque instance du NPC.

```python
on right click:
    if cooldown "dialogue" is ready:
        say local "Bonjour !"
        set cooldown "dialogue" for 5 seconds
```

Pour réinitialiser un cooldown : `reset cooldown "dialogue"`. Les durées acceptent les ticks, secondes, minutes et millisecondes.

### États

Un NPC possède un état principal et peut avoir plusieurs états secondaires.

```python
on right click:
    set state "patrouille"
    add state "vigilant"
    remove state "vigilant"
    clear states
    set health of npc to 20
    set max health of npc to 30
    set speed of npc to 3
```

Les états sont réinitialisés au redémarrage normal. Si les scripts du NPC sont désactivés, leur état courant est sauvegardé et restauré lors de la réactivation, même après un redémarrage.

## Commandes

- `/anpc list`
- `/anpc create <id> <modele|limb:nom>`
- `/anpc tp <id>`
- `/anpc center <id>`
- `/anpc rename <id> <nom>`
- `/anpc kill <id>` : retire temporairement les instances jusqu'au prochain rechargement.
- `/anpc remove <id>` : masque le NPC et désactive ses interactions sans modifier `npc.yml` ni son script.
- `/anpc restore <id>` : restaure un NPC masqué ou récupère une suppression datant de moins de 24 h.
- `/anpc delete <id>`, puis `/anpc delete <id> confirm` : suppression définitive après confirmation sous 30 secondes. La définition, le script `.aa` et les variables locales sont récupérables pendant 24 h.
- `/anpc disable <id>` et `/anpc enable <id>` : désactive/réactive les scripts sans retirer le modèle.
- `/anpc reload` : recharge la configuration, les NPC et tous les scripts sans effacer les variables SQLite. `/anpc reload <script>` recharge un fichier `.aa` précis et réindexe les fonctions globales.

Permission : `anpc.admin` (opérateur par défaut).

## Périmètre restant

Cette branche met en place le stockage par portée, les règles d'accès `shared`, le cycle de vie des NPC, les instances multi-mondes, la récupération sur 24 h, les commandes d'activation, les états, les fonctions et les boucles.

La liste complète des fonctionnalités validées n'est pas encore terminée : pathfinding et suivi avancés, combat/hostilité, interactions complètes entre NPC, simulation de blocs, tous les événements/conditions/actions du langage, gestion des skins par fichier local, intégrations MythicMobs/ItemsAdder et tests en jeu restent à implémenter ou vérifier. Ne pas considérer ces éléments comme disponibles tant qu'ils ne sont pas ajoutés et testés.
