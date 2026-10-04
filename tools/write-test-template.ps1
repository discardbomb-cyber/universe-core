$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.IO.Compression;
using System.Text;
public static class UniverseTestTemplate {
    static void I(Stream s, int n) { s.WriteByte((byte)(n>>24)); s.WriteByte((byte)(n>>16)); s.WriteByte((byte)(n>>8)); s.WriteByte((byte)n); }
    static void S(Stream s, string v) { var b=Encoding.UTF8.GetBytes(v); s.WriteByte((byte)(b.Length>>8)); s.WriteByte((byte)b.Length); s.Write(b,0,b.Length); }
    static void T(Stream s, byte t, string n) { s.WriteByte(t); S(s,n); }
    public static void Write(string path) {
        using(var f=File.Create(path)) using(var s=new GZipStream(f,CompressionMode.Compress)) {
            T(s,10,""); T(s,3,"DataVersion"); I(s,3955);
            T(s,9,"size"); s.WriteByte(3); I(s,3); I(s,3); I(s,3); I(s,3);
            T(s,9,"palette"); s.WriteByte(10); I(s,1); T(s,8,"Name"); S(s,"minecraft:air"); s.WriteByte(0);
            T(s,9,"blocks"); s.WriteByte(10); I(s,0);
            T(s,9,"entities"); s.WriteByte(10); I(s,0); s.WriteByte(0);
        }
    }
}
'@
$projectRoot = Split-Path $PSScriptRoot -Parent
$target = Join-Path $projectRoot 'src/main/resources/data/universe/structure/empty.nbt'
New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
[UniverseTestTemplate]::Write($target)
