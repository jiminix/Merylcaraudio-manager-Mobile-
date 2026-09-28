# MerylCarAudio Manager Mobile

Version mobile Android du **MerylCarPlay / MerylCarAudio Manager**, adaptée au Samsung A34 5G.

## V0.1

- Sélection du dossier `Music` de la carte SD avec le sélecteur Android.
- Accès persistant à la carte SD sans permission globale de stockage.
- Import d'un album ou d'un dossier contenant plusieurs albums.
- Lecture automatique des tags audio : artiste, album, année, piste, disque et compositeur.
- Recherche automatique de la pochette (`cover`, `front`, `folder`, `album`, etc.) ou extraction de l'image intégrée.
- File de 30 albums avec pochettes et suppression individuelle.
- Signalement immédiat d'un album déjà présent sur la SD.
- Affichage séparé : espace SD libre, taille estimée de l'envoi, espace restant et temps estimé.
- Conversion des titres en **MP3 128 kb/s**, tags ID3 et pochette intégrée, comme le Manager PC.
- Nommage de sortie : `Artiste Année Album`.
- Bouton d'annulation pendant la conversion.
- Explorateur simple des albums présents dans le dossier SD choisi.

## Utilisation sur le A34

1. Installer l'APK.
2. Appuyer sur **SD** et choisir le dossier `Music` de la carte microSD.
3. Appuyer sur **CHOISIR ALBUM** ou **PLUSIEURS**.
4. Vérifier artiste / année / album puis appuyer sur **AJOUTER**.
5. Appuyer sur **A34G · COPIER SUR SD**.

L'application utilise le **Storage Access Framework** Android : le dossier de la carte SD doit être choisi une première fois par l'utilisateur.

## APK GitHub

Chaque push sur `main` lance l'action **Build Android APK**. L'APK est disponible dans l'onglet **Actions** du dépôt, dans les artifacts du dernier build réussi.
