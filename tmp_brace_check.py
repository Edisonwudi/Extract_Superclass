from pathlib import Path
text = Path("src/main/java/com/refactoring/extractsuperclass/ExtractSuperclassRefactorer.java").read_text()
count = 0
for i, ch in enumerate(text):
    if ch == '{':
        count += 1
    elif ch == '}':
        count -= 1
        if count < 0:
            print("negative at", i)
            break
else:
    print("final count", count)
