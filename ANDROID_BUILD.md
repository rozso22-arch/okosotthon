# GépJózsi Android prototípus

Ez a projekt a meglévő OkosOtthon webalkalmazástól különálló Android APK-t készít.

## Első prototípus
- A készülék Android beszédfelismerőjét használja magyar (hu-HU) nyelven.
- A „GépJózsi” megszólítás után 10 másodpercig várja a kapcsolási parancsot.
- A Kint, Hátul, Bejárat és Szobák kapcsolókat a meglévő Cloudflare Worker /control végpontján keresztül vezérli.
- A vezérlési kulcsot az alkalmazás privát tárhelyén tárolja.
- Folyamatos értesítést mutat, amíg a mikrofonfigyelő szolgáltatás fut.

## APK elkészítése
A GitHub Actions automatikusan elindul az Android-fájlok módosításakor. Kézzel is indítható: GitHub → Actions → Build GepJozsi APK → Run workflow. A sikeres futás végén a GepJozsi-debug-apk artifactból letölthető az APK.

## Korlátok
Ez kísérleti első változat. Az Android SpeechRecognizer nem garantál folyamatos, megszakítás nélküli felismerést; rövid felismerési munkameneteket indít újra. A Google/Android beszédfelismerő szolgáltatás, az internet, az akkumulátorkezelés és a Xiaomi háttérkorlátozásai befolyásolják. A képernyő lezárása melletti működést a Redmi készüléken külön tesztelni kell. A mikrofonfigyelést a felhasználó indítja és az alkalmazásból leállíthatja.
