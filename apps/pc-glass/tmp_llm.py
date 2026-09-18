from pathlib import Path
# LLMProvider definition and config loading
ls=Path('artemis/config/llm.py').read_text(encoding='utf-8').splitlines()
for n in range(1, 250):
    print(f'{n}: {ls[n-1][:180]}')
