"""Validate using the vendored Bedrock Studio draft-07 schema, entirely offline."""
import json,sys
from pathlib import Path
from jsonschema import Draft7Validator
from referencing import Registry,Resource
vendor=Path(__file__).resolve().parent/'vendor'
schema=json.loads((vendor/'geometry.1.12.0.schema.json').read_text())
meta=json.loads((vendor/'meta.json').read_text())
registry=Registry().with_resource('https://bedrock-studio.github.io/bedrock-json-schemas/meta.json',Resource.from_contents(meta))
validator=Draft7Validator(schema,registry=registry)
for arg in sys.argv[1:]:
    data=json.loads(Path(arg).read_text())
    errors=list(validator.iter_errors(data))
    if errors:
        for e in errors[:10]: print(arg,list(e.path),e.message)
        raise SystemExit(1)
    print('PASS Bedrock geometry draft-07 schema:',arg)
