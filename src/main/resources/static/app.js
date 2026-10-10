// Calls the Nada API on this origin. The people table is only what this
// browser session has created. The server does not offer a full listing.

const PEOPLE = "/people";
const KNOWS = "/knows";
const PATH = "/path";
const SESSION_KEY = "nada.sessionPeople";

const people = loadPeople();

const statusDot = document.getElementById("status-dot");
const statusText = document.getElementById("status-text");
const baseUrl = document.getElementById("base-url");
const peopleBody = document.getElementById("people-body");

document.getElementById("create-person").addEventListener("submit", createPerson);
document.getElementById("contacts-form").addEventListener("submit", loadContacts);
document.getElementById("knows-form").addEventListener("submit", connectPeople);
document.getElementById("path-form").addEventListener("submit", findPath);
document.getElementById("clear-session").addEventListener("click", clearLocalList);

baseUrl.textContent = window.location.origin;
renderPeople();
checkServer();

function loadPeople() {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(SESSION_KEY) || "[]");
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter((person) => person
      && Number.isSafeInteger(person.id)
      && typeof person.name === "string");
  } catch (error) {
    return [];
  }
}

function persistPeople() {
  try {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(people));
  } catch (error) {
    // The table still shows people created during this page view.
  }
}

function clearLocalList() {
  people.splice(0, people.length);
  sessionStorage.removeItem(SESSION_KEY);
  renderPeople();
}

async function checkServer() {
  statusText.textContent = "Checking the API…";
  statusDot.className = "dot";
  try {
    const response = await fetch(PATH, { cache: "no-store" });
    await response.text();
    statusDot.className = "dot ok";
    statusText.textContent = "API reachable";
  } catch (error) {
    statusDot.className = "dot bad";
    statusText.textContent = "API unreachable. The page is open, but requests to the server are failing.";
  }
}

function renderPeople() {
  peopleBody.replaceChildren();
  if (people.length === 0) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 3;
    cell.textContent = "No people created in this browser session yet.";
    row.append(cell);
    peopleBody.append(row);
    return;
  }
  const ordered = people.slice().sort((left, right) => left.id - right.id);
  ordered.forEach((person) => {
    const row = document.createElement("tr");
    const idCell = document.createElement("td");
    idCell.textContent = String(person.id);
    const nameCell = document.createElement("td");
    nameCell.textContent = person.name;
    const actionCell = document.createElement("td");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "ghost";
    button.textContent = "Contacts";
    button.setAttribute("aria-label", "Show contacts for person " + person.id);
    button.addEventListener("click", () => {
      document.getElementById("contacts-id").value = String(person.id);
      document.getElementById("contacts-form").requestSubmit();
    });
    actionCell.append(button);
    row.append(idCell, nameCell, actionCell);
    peopleBody.append(row);
  });
}

function rememberPerson(body) {
  if (!body || typeof body.name !== "string" || !Number.isSafeInteger(body.id)) {
    return false;
  }
  const existing = people.find((person) => person.id === body.id);
  if (existing) {
    existing.name = body.name;
  } else {
    people.push({ id: body.id, name: body.name });
  }
  persistPeople();
  renderPeople();
  return true;
}

async function createPerson(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const input = document.getElementById("person-name");
  const name = input.value;
  if (name.trim() === "") {
    showLocal("create-feedback", "Name must not be blank.", "person-name");
    return;
  }
  const button = submitButton(form);
  const result = await callApi("POST", PEOPLE, { name: name }, button);
  const remembered = result.json && rememberPerson(result.json);
  const extra = remembered ? "Created person " + result.json.id + "." : "";
  showResult("create-feedback", "create-response", result, extra);
  if (remembered) {
    input.value = "";
  }
}

async function loadContacts(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const parsed = parseId(document.getElementById("contacts-id").value, "Person ID");
  if (parsed.error) {
    showLocal("contacts-feedback", parsed.error, "contacts-id");
    return;
  }
  const endpoint = PEOPLE + "/" + encodeURIComponent(String(parsed.value)) + "/contacts";
  const result = await callApi("GET", endpoint, undefined, submitButton(form));
  renderContacts(result);
  const extra = result.status === 200 && Array.isArray(result.json) && result.json.length === 0
    ? "No direct contacts."
    : "";
  showResult("contacts-feedback", "contacts-response", result, extra);
}

function renderContacts(result) {
  const list = document.getElementById("contacts-list");
  list.replaceChildren();
  if (!result.json || !Array.isArray(result.json)) {
    return;
  }
  if (result.json.length === 0) {
    const item = document.createElement("li");
    item.textContent = "No direct contacts.";
    list.append(item);
    return;
  }
  result.json.forEach((person) => {
    const item = document.createElement("li");
    const name = person && typeof person.name === "string" ? person.name : "";
    const id = person && person.id !== undefined ? String(person.id) : "";
    item.textContent = id + " — " + name;
    list.append(item);
  });
}

async function connectPeople(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const a = parseId(document.getElementById("knows-a").value, "Person A");
  const b = parseId(document.getElementById("knows-b").value, "Person B");
  if (a.error || b.error) {
    showLocal("knows-feedback", a.error || b.error, a.error ? "knows-a" : "knows-b");
    return;
  }
  if (a.value === b.value) {
    showLocal("knows-feedback", "A person cannot be connected to themselves.", "knows-b");
    return;
  }
  const result = await callApi("POST", KNOWS, { a: a.value, b: b.value }, submitButton(form));
  const extra = result.status === 204 ? "Connection recorded." : "";
  showResult("knows-feedback", "knows-response", result, extra);
}

async function findPath(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const from = parseId(document.getElementById("path-from").value, "From");
  const to = parseId(document.getElementById("path-to").value, "To");
  if (from.error || to.error) {
    document.getElementById("path-nodes").replaceChildren();
    document.getElementById("path-response").textContent = "No request sent.";
    showLocal("path-summary", from.error || to.error, from.error ? "path-from" : "path-to");
    return;
  }
  const endpoint = PATH
    + "?from=" + encodeURIComponent(String(from.value))
    + "&to=" + encodeURIComponent(String(to.value));
  const summary = document.getElementById("path-summary");
  const nodes = document.getElementById("path-nodes");
  summary.textContent = "Loading…";
  summary.className = "feedback";
  nodes.replaceChildren();
  const result = await callApi("GET", endpoint, undefined, submitButton(form));
  showResult("path-summary", "path-response", result, "");
  renderPath(result);
}

function renderPath(result) {
  const summary = document.getElementById("path-summary");
  const nodes = document.getElementById("path-nodes");
  nodes.replaceChildren();
  if (!result.json || result.status !== 200) {
    return;
  }
  if (result.json.connected === false) {
    summary.textContent = "Not connected. No path of three hops or fewer was found.";
    summary.className = "feedback bad";
    return;
  }
  if (result.json.connected !== true || !Array.isArray(result.json.path)) {
    summary.textContent = "HTTP " + result.status + ". The response did not include a path.";
    summary.className = "feedback bad";
    return;
  }
  const hops = result.json.hops;
  summary.textContent = "Connected. Hops: " + hops + ".";
  summary.className = "feedback ok";
  result.json.path.forEach((person) => {
    const item = document.createElement("li");
    const node = document.createElement("div");
    node.className = "node";
    const name = document.createElement("span");
    name.className = "node-name";
    name.textContent = person && typeof person.name === "string" ? person.name : "";
    const id = document.createElement("span");
    id.className = "node-id";
    id.textContent = person && person.id !== undefined ? "id " + person.id : "";
    node.append(name, id);
    item.append(node);
    nodes.append(item);
  });
}

function parseId(raw, label) {
  const trimmed = String(raw).trim();
  if (trimmed === "") {
    return { error: label + " is required." };
  }
  if (!/^[+-]?\d+$/.test(trimmed)) {
    return { error: label + " must be an integer." };
  }
  const value = Number(trimmed);
  if (!Number.isSafeInteger(value)) {
    return { error: label + " is outside the range this page can send safely." };
  }
  return { value: value };
}

function showLocal(feedbackId, message, inputId) {
  const feedback = document.getElementById(feedbackId);
  feedback.textContent = message;
  feedback.className = "feedback bad";
  const field = document.getElementById(inputId);
  if (field) {
    field.setAttribute("aria-invalid", "true");
    field.focus();
  }
}

function showResult(feedbackId, outputId, result, extra) {
  const feedback = document.getElementById(feedbackId);
  const output = document.getElementById(outputId);
  if (result.networkError) {
    feedback.textContent = result.networkError;
    feedback.className = "feedback bad";
    output.textContent = result.networkError;
    return;
  }
  if (result.parseError) {
    feedback.textContent = "HTTP " + result.status + ". " + result.parseError;
    feedback.className = "feedback bad";
    output.textContent = result.text;
    return;
  }
  output.textContent = pretty(result);
  if (result.status >= 200 && result.status < 300) {
    feedback.textContent = extra ? "HTTP " + result.status + ". " + extra : "HTTP " + result.status + ".";
    feedback.className = "feedback ok";
    return;
  }
  const reason = result.json && typeof result.json.error === "string" ? " " + result.json.error : "";
  feedback.textContent = "HTTP " + result.status + "." + reason;
  feedback.className = "feedback bad";
}

function pretty(result) {
  if (!result.text) {
    return "(empty body)";
  }
  if (result.json !== null && result.json !== undefined) {
    return JSON.stringify(result.json, null, 2);
  }
  return result.text;
}

async function callApi(method, endpoint, body, button) {
  setBusy(button, true);
  setInspector(method, endpoint, "…", "Waiting for the server…");
  try {
    const options = { method: method, cache: "no-store", headers: {} };
    if (body !== undefined) {
      options.headers["Content-Type"] = "application/json; charset=UTF-8";
      options.body = JSON.stringify(body);
    }
    const response = await fetch(endpoint, options);
    const text = await response.text();
    let json = null;
    let parseError = null;
    if (text) {
      try {
        json = JSON.parse(text);
      } catch (error) {
        parseError = "The response was not valid JSON.";
      }
    }
    const result = {
      status: response.status,
      text: text,
      json: json,
      parseError: parseError,
      networkError: null
    };
    setInspector(method, endpoint, String(response.status), result.parseError || pretty(result));
    return result;
  } catch (error) {
    const message = "Could not reach the server at " + window.location.origin + ".";
    setInspector(method, endpoint, "network error", message);
    return { status: 0, text: "", json: null, parseError: null, networkError: message };
  } finally {
    setBusy(button, false);
  }
}

function setInspector(method, endpoint, status, body) {
  document.getElementById("inspector-method").textContent = method;
  document.getElementById("inspector-endpoint").textContent = endpoint;
  document.getElementById("inspector-status").textContent = status;
  document.getElementById("inspector-body").textContent = body;
}

function submitButton(form) {
  return form.querySelector("button[type='submit']");
}

function setBusy(button, busy) {
  if (!button) {
    return;
  }
  if (busy && button.form) {
    button.form.querySelectorAll("[aria-invalid]").forEach((field) => {
      field.removeAttribute("aria-invalid");
    });
  }
  button.disabled = busy;
  if (busy) {
    button.dataset.label = button.textContent;
    button.textContent = "Working…";
    return;
  }
  if (button.dataset.label) {
    button.textContent = button.dataset.label;
  }
}
