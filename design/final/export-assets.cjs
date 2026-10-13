// Run from the repository root: node design/final/export-assets.cjs
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const context={window:{}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'brand.js'),'utf8'),context);
const brand=context.window.YeobaekBrand;
const output=path.join(__dirname,'assets');
fs.mkdirSync(output,{recursive:true});
for(const mode of ['light','dark']){
  fs.writeFileSync(path.join(output,`icon-${mode}.svg`),brand.svg(mode));
  fs.writeFileSync(path.join(output,`symbol-${mode}.svg`),brand.svg(mode,'symbol'));
  fs.writeFileSync(path.join(output,`background-${mode}.svg`),`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="${brand.palette[mode].background}"/></svg>`);
}
fs.writeFileSync(path.join(output,'symbol-monochrome.svg'),brand.svg('light','monochrome'));
console.log('Exported 7 approved brand SVGs.');
