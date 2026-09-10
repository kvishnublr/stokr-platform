import sys

file_path = '/root/stokr-platform/stokr-lite/frontend/index.html'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

snippet = '''
    <script>
      if ('serviceWorker' in navigator) {
        navigator.serviceWorker.getRegistrations().then(function(registrations) {
          for(let registration of registrations) {
            registration.unregister();
          }
        });
      }
    </script>
'''

if 'registration.unregister()' not in content:
    content = content.replace('</body>', snippet + '\n  </body>')
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
