# marc-validate
A command-line utility for streaming marcxml schema validation, 
with tailored logging to identify specific records and locations
of validation errors.  

Will run against arbitrarily large xml files with minimal memory
overhead.

```
USAGE: java -jar marc-validate.jar [-h] [-z] [-r] [input-file]
  -h: print this help message
  -z: expect gzipped input
  -r: replace malformed character encoding
  [input-file]: input file; '-' or unspecified for stdin
```
