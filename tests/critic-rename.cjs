const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const ROOT=path.resolve(__dirname,'..');
const source=fs.readFileSync(path.join(ROOT,'main.cjs'),'utf8');
const section=source.slice(source.indexOf("case 'rename':{"),source.indexOf("case 'copyFile':{"));
const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor;
const run=new AsyncFunction('fs','path','fileTask','call','d','switch("rename"){'+section+'}');
(async()=>{const dir=path.join(ROOT,'verification','critic-r1-rename');fs.mkdirSync(dir,{recursive:true});const original=path.join(dir,'original.mp4'),renamed=path.join(dir,'renamed.mp4');fs.writeFileSync(original,Buffer.from('owned fixture'));try{fs.unlinkSync(renamed)}catch{};
let error='';try{await run(fs,path,async()=>original,async()=>{throw Error('Simulated engine metadata rejection')},{id:'fixture',name:'renamed.mp4'})}catch(e){error=e.message}
assert.equal(error,'Simulated engine metadata rejection');
const result={tested:'Exact main.cjs rename action with metadata rejection after external file copy',originalStillExists:fs.existsSync(original),newFileExists:fs.existsSync(renamed),metadataStillPointsTo:original,error};
fs.writeFileSync(path.join(dir,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
assert.equal(result.originalStillExists,false,'Expected to reproduce removed original');assert.equal(result.newFileExists,true,'Expected preserved data at uncommitted name');
})().catch(e=>{console.error(e);process.exitCode=1});
