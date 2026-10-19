# __NAME__

__SUMMARY__

Type: __TYPE__

## GitHub Actions se build kaise karein

1. github.com par naya repository banayein.
2. Is ZIP ki saari files repo mein upload karein, hidden `.github` folder samet.
   - PC par: `git init && git add . && git commit -m "first" && git push`
   - Phone par folder upload nahi hota. Wahan "Add file > Create new file" dabayein, naam likhein `.github/workflows/build.yml`, aur ZIP wali `.github/workflows/build.yml` ka content paste karein. Baaki files upload kar dein.
3. Repo ke "Actions" tab mein build apne aap chalega (ya "Run workflow" dabayein).
4. Build khatam hone par run ke neeche "Artifacts" mein __OUTPUT__ download karein.
5. Build fail ho to failed step ka log kholkar error copy karein aur AI ko dein, wo fix bata dega.

## Note
Ye project AI ne banaya hai. Pehli baar mein chhoti galtiyan ho sakti hain (imports, versions), isliye build log zaroor dekhein.
