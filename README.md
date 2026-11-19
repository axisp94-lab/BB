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

Les variables sont conservées dans SQLite. Les variables locales sont isolées par NPC et par monde. Les variables globales et partagées restent communes selon leur portée. La liste des membres d’une variable `shared` est fixée lors de sa première déclaration : une redéclaration avec une liste différente est refusée pour éviter de laisser d’anciens accès.

### Fonctions globales

Les fonctions sont accessibles depuis tous les scripts NPC. La syntaxe recommandée est `def nom(paramètres):` avec appel direct ; l'ancienne syntaxe `function` et le préfixe `call` restent compatibles. Les paramètres sont locaux à l'appel et `return` renvoie une valeur. Une fonction contenant `wait` doit être appelée comme instruction autonome.

```python
def saluer(nom):
    return "Bonjour " + nom

on right click:
    set "message" saluer("visiteur")
    say local message
```

### Attentes asynchrones

`wait` suspend puis reprend le script sans bloquer le thread serveur. Les continuations conservent le contexte des blocs conditionnels, des boucles et des appels de fonction autonomes (`call nom_fonction(...)`). Les attentes successives sont exécutées dans l'ordre. Les durées acceptées sont les ticks (unité par défaut), les secondes, les minutes et les millisecondes ; une durée invalide produit un avertissement et n'est pas exécutée.

Une fonction contenant `wait` doit être appelée comme instruction autonome. Elle ne doit pas être appelée dans une expression telle que `set "x" (call ma_fonction())`, car une expression doit renvoyer sa valeur immédiatement.

```python
function saluer():
    say local "Bonjour !"
    wait 2 seconds
    say local "À bientôt !"

on right click:
    loop 2 times:
        if player is sneaking:
            call saluer()
        wait 5 ticks
```

### Conditions et expressions

Les comparaisons prennent en charge `==`, `!=`, `>`, `<`, `>=` et `<=`, ainsi que `and`, `or` et `not`.

```python
if block below is stone:
    say local "Je suis sur de la pierre."
if block below is not air:
    say local "Je suis posé."
if block below is one of (stone, dirt):
    say local "Sol naturel."
if ray = 3 contains stone:
    say local "Pierre dans le rayon horizontal."
if sphere = 3 contains stone:
    say local "Pierre dans la sphère."
if number of stone in sphere 3 > 5:
    say local "Plus de cinq blocs de pierre."
if (liste) contains (cle) more than 2 times:
    say local "La valeur apparaît plusieurs fois."
```

`ray` et `range` inspectent le disque horizontal autour du NPC, au niveau de ses pieds ; `sphere` inspecte une sphère 3D. Le rayon accepté est compris entre 0 et 32 blocs.

### Variables intégrées et sélecteurs

Les propriétés courantes comprennent notamment `name/id/type/skin/location/world/x/y/z/yaw/pitch/health/speed of npc`, ainsi que les propriétés de joueur comme `name/uuid/location/world/x/y/z/health/food level/level/game mode of player`. Les propriétés d'un autre NPC ou joueur peuvent être demandées avec `name of npc:gardien` ou `name of player:Axis`.

Les sélecteurs `@p`, `@a`, `@n`, `@e` et `@e[type=zombie]` sont résolus dans le monde du NPC. Les décalages de position sont acceptés sous la forme `move to @p + 1` ou `move to @p + (1, 0, 0)`.

### Flags

```python
create flag (porte) toggle
create flag (alerte) input
flag on (porte)
flag send (alerte) to gardien, marchand

on flag receive (alerte):
    say local "J'ai reçu une alerte."
```

Un flag `toggle` se lit avec `if flag (porte) is on:` et se modifie avec `flag on/off`. Un flag `input` transmet un événement à un ou plusieurs NPC, ou à `all`.

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
    set attack damage of npc to 4
```

Les propriétés `health of npc`, `max health of npc`, `speed of npc` et `attack damage of npc` sont accessibles dans les expressions. `set attack damage of npc to <valeur>` règle les dégâts de combat propres à cette instance. Les états sont réinitialisés au redémarrage normal. Si les scripts du NPC sont désactivés, leur état courant est sauvegardé et restauré lors de la réactivation, même après un redémarrage.

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

## État d'avancement

**Implémenté dans le code et compilé :**
- Stockage SQLite des variables `local`, `global` et `shared`, avec contrôle des membres autorisés et isolation locale par monde.
- Instances multi-mondes, commandes `enable/disable`, masquage/restauration, suppression confirmée et récupération 24 h des définitions, scripts et variables locales, y compris pour les mondes non chargés.
- Navigation au sol et en vol, pathfinding limité par `movement.max-path-attempts`, checkpoints, suivi et maintien de distance.
- Combat avec sélection de cibles, priorité au dernier attaquant, dégâts configurables et dégâts propres à chaque NPC.
- Simulation de blocs, sons vanilla/ItemsAdder, animations, états, cooldowns, fonctions globales, paramètres/retours, boucles et plusieurs événements/conditions.
- Skins PNG locaux et profils par UUID/pseudo.

**Encore incomplet ou à valider en jeu :**
- Interactions complètes entre NPC, notamment les combats NPC contre NPC et les règles de ciblage des mobs vers les NPC.
- Équipement visuel des NPC et couverture complète des attributs/items.
- Couverture exhaustive des événements, conditions et actions `.aa`. Les événements de zone `entity enter/exit` et certains événements de placement restent à préciser ; les appels de fonction contenant `wait` dans une expression restent volontairement non pris en charge.
- Validation réelle sur Paper 1.21.11 avec BetterModel 2.2.0, puis tests des intégrations MythicMobs et ItemsAdder sur serveur.
- Tests d'intégration en jeu : les tests automatisés actuels couvrent le stockage SQLite, pas le comportement complet de Paper/BetterModel.

La réussite du workflow Maven confirme la compilation et les tests automatisés ; elle ne remplace pas les essais sur un serveur Minecraft réel.
