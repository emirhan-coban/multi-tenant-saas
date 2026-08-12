import { useEffect, useState } from 'react';
import { Text, View, StyleSheet, Platform } from 'react-native';
import axios from 'axios';

const API = Platform.OS === 'web'
    ? 'http://localhost:8080'
    : 'http://192.168.1.161:8080';

export default function App() {
  const [status, setStatus] = useState('bağlanıyor...');

  useEffect(() => {
    axios.get(`${API}/api/health`)
        .then(r => setStatus('✅ ' + JSON.stringify(r.data)))
        .catch(e => setStatus('❌ ' + e.message));
  }, []);

  return (
      <View style={styles.container}>
        <Text style={styles.text}>{status}</Text>
      </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  text: { fontSize: 18 },
});