# Wyrix App Generator AI

Android app: ek prompt likho, AI poora project (Android app, website, full-stack, extension, browser app...) file-by-file likhta hai aur ZIP deta hai. ZIP ke andar GitHub Actions ka build workflow pehle se hota hai.

## Is app ko GitHub Actions se build karein
1. Naya GitHub repo banayein aur is ZIP ki saari files upload karein (hidden `.github` folder samet).
2. Actions tab mein "Build APK" chalega (ya "Run workflow" dabayein).
3. Build ke baad "Artifacts" se `app-debug-apk` download karke install karein.

## App kaise kaam karta hai
1. Prompt likhein.
2. Planner model blueprint banata hai: file list, har file ka kaam, style guide.
3. Aap plan dekhkar approve karte hain.
4. Har file alag request se banti hai (blueprint aur dependency files ke saath), taaki 50k+ lines tak project ban sake.
5. Har file par local check (khali file, placeholder, adhoori braces, invalid JSON); problem ho to ek baar dobara likhi jaati hai.
6. Reviewer model cross-file galtiyan dhundhkar unhe theek karta hai.
7. Workflow, README aur .gitignore judkar ZIP Downloads folder mein save hoti hai.

## Settings
- API keys: OpenAI, Anthropic, Gemini, Groq, NVIDIA, DeepSeek, Kimi, OpenRouter. Sirf phone mein encrypted rehti hain.
- Models list: har line `tier|provider|model`. Upar wala pehle try hota hai. Limit (429) ya error par model kuch der cooldown mein jata hai aur agla model le leta hai. Model ke naam providers badalte rehte hain, isliye list yahin se update karein.

## Limits
- Free tiers ki limits jaldi khatam ho sakti hain; badi projects mein lakhon tokens lagte hain.
- Phone par code compile nahi hota, isliye build ke baad errors aa sakte hain. Build log AI ko dikhakar fix karwayein.
