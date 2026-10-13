// Run: node design/archive/icons-v3/export-assets.cjs
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const context = {window:{}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'catalog.js'),'utf8'),context);
const {YeobaekIcons,YeobaekIconPalette,yeobaekIconSvg} = context.window;
const destination = path.join(__dirname,'assets');
fs.mkdirSync(destination,{recursive:true});
for (const icon of YeobaekIcons) {
  for (const mode of ['light','dark']) {
    fs.writeFileSync(path.join(destination,`${icon.id}-${mode}.svg`),yeobaekIconSvg(icon.id,mode));
    fs.writeFileSync(path.join(destination,`${icon.id}-foreground-${mode}.svg`),yeobaekIconSvg(icon.id,mode,'foreground'));
  }
  fs.writeFileSync(path.join(destination,`${icon.id}-monochrome.svg`),yeobaekIconSvg(icon.id,'light','monochrome'));
}
for (const mode of ['light','dark']) {
  fs.writeFileSync(path.join(destination,`background-${mode}.svg`),`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="${YeobaekIconPalette[mode].background}"/></svg>`);
}
console.log('Exported 27 SVG assets.');
