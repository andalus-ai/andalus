import sys

with open('src/main/java/tech/kayys/andalus/provider/aqli/AqliInferenceProvider.java', 'r') as f:
    content = f.read()

# Replace setModelId(model) with setModelId("default")
content = content.replace('.setModelId(model)', '.setModelId("default")')

# Replace the catch block in fetch to print the error
old_catch = """                } catch (Exception e) {
                    done = true;
                }"""
new_catch = """                } catch (Exception e) {
                    System.err.println("[Aqli] gRPC stream failed: " + e.getMessage());
                    e.printStackTrace();
                    done = true;
                }"""
content = content.replace(old_catch, new_catch)

with open('src/main/java/tech/kayys/andalus/provider/aqli/AqliInferenceProvider.java', 'w') as f:
    f.write(content)

