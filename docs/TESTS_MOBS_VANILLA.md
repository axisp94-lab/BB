# Plan de test — NPC mobs vanilla

Environnement cible : Paper 1.21.11, Java 21, BetterModel 2.2.0, AmethyysNPCs 0.1.0.

Ces essais doivent être réalisés sur un serveur de test ou une copie du serveur. Sauvegarder le dossier `plugins/AmethyysNPCs/` avant installation.

## Préparation

1. Arrêter le serveur.
2. Remplacer le JAR d'AmethyysNPCs par celui de la livraison.
3. Démarrer le serveur et vérifier qu'aucune erreur de chargement du plugin n'apparaît.
4. Exécuter `/anpc help`.
5. Tester la complétion après `/anpc create test_` : les identifiants de mobs vanilla doivent être proposés.

## Tests fonctionnels

| ID | Essai | Résultat attendu |
|---|---|---|
| VM-01 | `/anpc create test_zombie zombie` | Le zombie apparaît au joueur, immobile, avec le nom du NPC. |
| VM-02 | `/anpc create test_allay allay` | L'Allay apparaît sans voler ni suivre un joueur. |
| VM-03 | `/anpc create test_villager villager` | Le villageois apparaît sans se déplacer ni changer de profession. |
| VM-04 | `/anpc create test_breeze breeze` | Le Breeze apparaît et ne lance pas d'attaque autonome. |
| VM-05 | `/anpc create test_dragon minecraft:ender_dragon` | Le type boss est accepté si Paper peut créer cette entité dans le monde de test. |
| VM-06 | Clic droit sur un NPC vanilla | L'événement `on right click` du fichier `.aa` associé se déclenche une seule fois. |
| VM-07 | Frapper un NPC vanilla | Les dégâts sont annulés et les événements de script prévus sont déclenchés. |
| VM-08 | `/anpc rename test_zombie &aGardien` | Le nom visible de l'entité change sans la recréer. |
| VM-09 | `/anpc kill test_zombie` | L'entité disparaît et la définition est supprimée de `npc.yml`. |
| VM-10 | Recréer un NPC, décharger puis recharger son chunk | Le NPC est retiré au déchargement puis recréé une seule fois au rechargement. |
| VM-11 | `/anpc reload` | Les entités gérées sont recréées une seule fois, sans doublons. |
| VM-12 | Utiliser un identifiant non-mob, par exemple `arrow` | Il n'est pas proposé comme mob vanilla et ne doit pas être créé via l'adaptateur vanilla. |

## À transmettre en cas d'échec

- L'identifiant du test (VM-01 à VM-12).
- Les lignes de console depuis le démarrage ou depuis la commande testée.
- Le résultat de `/anpc list`.
- Une capture en jeu si le problème concerne l'apparence ou la position.

## Limites à vérifier

Le support vise les types vanilla exposés par Paper comme des entités `Mob`. Les projectiles, objets, véhicules, supports d'armure et autres entités non-mobs ne sont pas inclus. Les boss et entités spéciales peuvent avoir des particularités propres à leur type ; leur apparition doit être vérifiée en jeu.
