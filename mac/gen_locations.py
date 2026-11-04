# Locations.swift з android/.../Locations.java (ті самі області й райони, uid alerts.in.ua)
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
def arr(name):
    return re.search(r"static final \w+\[\] " + name + r" = \{(.*?)\};", src, re.S).group(1).strip()
out = ["// Згенеровано mac/gen_locations.py з android/src/ua/spokiy/trevoga/Locations.java — не редагувати вручну.",
       "enum Locations {"]
for java, swift, typ in [("RAION_UID", "raionUid", "Int"), ("RAION_OBLAST", "raionOblast", "Int"), ("RAION_NAME", "raionName", "String"),
                         ("OBLAST_NAME", "oblastName", "String"), ("OBLAST_UID", "oblastUid", "Int")]:
    out.append(f"    static let {swift}: [{typ}] = [{arr(java)}]")
out.append("    static func oblastUid(_ name: String) -> Int { if let i = oblastName.firstIndex(of: name) { return oblastUid[i] }; return -1 }")
out.append("}")
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(out) + "\n")
